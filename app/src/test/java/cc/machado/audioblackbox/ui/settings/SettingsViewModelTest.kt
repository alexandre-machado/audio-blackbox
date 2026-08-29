package cc.machado.audioblackbox.ui.settings

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.settings.ClampNotice
import cc.machado.audioblackbox.settings.InMemoryRetentionWindowPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pending-vs-committed model issue #73 requires: the stepper's `-`/`+` only ever move a local
 * value, and only [SettingsViewModel.commitPendingRetentionWindow] can ever persist/rebuild --
 * with the discard-confirmation dialog firing at most once per commit, never once per tap.
 *
 * Same shape as the old `DashboardViewModelRetentionWindowTest` this supersedes: every test asserts
 * on the exact side effects (whether `onRebuildEngine`/`retentionWindowPreferences` were actually
 * invoked, and with what), not a re-derivation of the production `when`/mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        cc.machado.audioblackbox.service.RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            newPreset = cc.machado.audioblackbox.audio.QualityPreset.DEFAULT,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        cc.machado.audioblackbox.service.RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            newPreset = cc.machado.audioblackbox.audio.QualityPreset.DEFAULT,
        )
    }

    // ---- Stepper: bounds and step arithmetic ----

    @Test
    fun `incrementPending moves by the step and stops at the maximum bound`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(40),
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        runCurrent()
        assertEquals(45, observed.last().retentionStepper.pendingMinutes)
        assertFalse("45 is the interim max (issue #72) -- + must now be disabled", observed.last().retentionStepper.canIncrement)

        vm.incrementPending()
        runCurrent()
        assertEquals("incrementing past the max must not overshoot it", 45, observed.last().retentionStepper.pendingMinutes)

        job.cancel()
    }

    @Test
    fun `decrementPending moves by the step and stops at the minimum bound`() = runTest(testDispatcher) {
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(10),
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.decrementPending()
        runCurrent()
        assertEquals(5, observed.last().retentionStepper.pendingMinutes)
        assertFalse("5 is the min -- - must now be disabled", observed.last().retentionStepper.canDecrement)

        vm.decrementPending()
        runCurrent()
        assertEquals("decrementing past the min must not undershoot it", 5, observed.last().retentionStepper.pendingMinutes)

        job.cancel()
    }

    @Test
    fun `stepping the pending value never persists or rebuilds anything on its own`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.incrementPending()
        vm.decrementPending()
        runCurrent()

        assertEquals(35, observed.last().retentionStepper.pendingMinutes)
        assertTrue("the pending value must be shown as not-yet-active", observed.last().retentionStepper.isDirty)
        assertTrue("stepping alone must never rebuild", rebuildCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())

        job.cancel()
    }

    // ---- Commit: pending-vs-committed and the discard dialog ----

    @Test
    fun `committing while Idle applies immediately -- nothing buffered, nothing to lose`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.commitPendingRetentionWindow()
        runCurrent()

        assertEquals(listOf(35 to cc.machado.audioblackbox.audio.QualityPreset.VOICE), rebuildCalls)
        assertEquals(35, preferences.currentBufferDurationMinutes())
        assertNull(
            "an Idle-engine commit must never show the discard dialog",
            observed.last().retentionStepper.pendingConfirmationMinutes,
        )

        job.cancel()
    }

    @Test
    fun `committing while Recording does not persist or rebuild -- it only surfaces the confirmation once`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        var stopCalls = 0
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.incrementPending()
        vm.incrementPending()
        vm.commitPendingRetentionWindow()
        runCurrent()

        assertTrue(
            "a single commit must not rebuild before confirmation, regardless of how many taps preceded it",
            rebuildCalls.isEmpty(),
        )
        assertEquals(0, stopCalls)
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertEquals(45, observed.last().retentionStepper.pendingConfirmationMinutes)

        job.cancel()
    }

    @Test
    fun `a second commit tap while a confirmation is already pending does not re-show or swap it`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { _, _ -> true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.commitPendingRetentionWindow()
        runCurrent()
        assertEquals(35, observed.last().retentionStepper.pendingConfirmationMinutes)

        vm.commitPendingRetentionWindow()
        runCurrent()

        assertEquals(
            "a stray second tap on Apply while the dialog is up must not replace the pending confirmation",
            35,
            observed.last().retentionStepper.pendingConfirmationMinutes,
        )

        job.cancel()
    }

    @Test
    fun `stepping while a confirmation is pending is locked -- it cannot move the value out from under the dialog`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { _, _ -> true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.commitPendingRetentionWindow()
        runCurrent()
        assertEquals(35, observed.last().retentionStepper.pendingMinutes)

        vm.incrementPending()
        runCurrent()

        assertEquals(
            "the stepper must stay locked at the value the pending confirmation refers to",
            35,
            observed.last().retentionStepper.pendingMinutes,
        )

        job.cancel()
    }

    @Test
    fun `confirming the change stops the engine, waits for Idle, then persists and rebuilds`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        var stopCalls = 0
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Recording)
        val capacityMinutesFlow = MutableStateFlow(30)
        val vm = SettingsViewModel(
            captureState = captureState,
            capacityMinutesFlow = capacityMinutesFlow,
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onRebuildEngine = { minutes, preset ->
                rebuildCalls += (minutes to preset)
                capacityMinutesFlow.value = minutes
                true
            },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.commitPendingRetentionWindow()
        runCurrent()
        assertEquals(35, observed.last().retentionStepper.pendingConfirmationMinutes)

        vm.confirmRetentionWindowChange()
        runCurrent()

        assertEquals(1, stopCalls)
        assertTrue(
            "must not persist/rebuild before captureState actually reaches Idle",
            rebuildCalls.isEmpty(),
        )
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertNull(
            "the dialog itself must close as soon as the user confirms, independent of the engine",
            observed.last().retentionStepper.pendingConfirmationMinutes,
        )

        captureState.value = CaptureState.Idle
        runCurrent()

        assertEquals(listOf(35 to cc.machado.audioblackbox.audio.QualityPreset.VOICE), rebuildCalls)
        assertEquals(35, preferences.currentBufferDurationMinutes())
        assertFalse(
            "once committed, pending and committed agree again",
            observed.last().retentionStepper.isDirty,
        )

        job.cancel()
    }

    @Test
    fun `cancelling the change leaves the engine and the persisted value untouched, and keeps the pending value`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        var stopCalls = 0
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.commitPendingRetentionWindow()
        runCurrent()
        assertEquals(35, observed.last().retentionStepper.pendingConfirmationMinutes)

        vm.cancelRetentionWindowChange()
        runCurrent()

        assertNull(observed.last().retentionStepper.pendingConfirmationMinutes)
        assertEquals(0, stopCalls)
        assertTrue(rebuildCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertEquals(35, observed.last().retentionStepper.pendingMinutes)

        job.cancel()
    }

    @Test
    fun `committing the already-current value is a no-op`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.commitPendingRetentionWindow()
        runCurrent()

        assertTrue("committing the current value must not rebuild or persist anything", rebuildCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertNull(observed.last().retentionStepper.pendingConfirmationMinutes)

        job.cancel()
    }

    // ---- Issue #193: Quality Preset selection and bounds ----

    @Test
    fun `selecting a quality preset marks state as dirty and adjusts max selectable retention`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            qualityPresetFlow = MutableStateFlow(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
            maxMemoryBytesProvider = { 256 * 1024 * 1024L },
            usedMemoryBytesProvider = { 30 * 1024 * 1024L },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.VOICE, observed.last().selectedPreset)
        assertFalse(observed.last().retentionStepper.isDirty)

        vm.selectQualityPreset(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY)
        runCurrent()

        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, observed.last().selectedPreset)
        assertTrue(observed.last().retentionStepper.isDirty)
        // High fidelity on a 256MB heap has a lower ceiling than 30 min -- pending minutes should be clamped down
        assertTrue(observed.last().retentionStepper.pendingMinutes <= observed.last().retentionStepper.maxSelectableMinutes)

        vm.commitPendingRetentionWindow()
        runCurrent()

        assertEquals(1, rebuildCalls.size)
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, rebuildCalls.first().second)
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, preferences.currentQualityPreset())

        job.cancel()
    }

    // ---- Clamp-down notice (issue #84) ----

    @Test
    fun `uiState surfaces a pending clamp notice from the preferences layer`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(
            initialMinutes = 45,
            initialClampNotice = ClampNotice(previousMinutes = 60, newMinutes = 45),
        )
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(45),
            retentionWindowPreferences = preferences,
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        assertEquals(60, observed.last().clampNotice?.previousMinutes)
        assertEquals(45, observed.last().clampNotice?.newMinutes)

        job.cancel()
    }

    @Test
    fun `acknowledgeClampNotice clears it from uiState and persists the acknowledgement`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(
            initialMinutes = 45,
            initialClampNotice = ClampNotice(previousMinutes = 60, newMinutes = 45),
        )
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(45),
            retentionWindowPreferences = preferences,
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()
        assertTrue(observed.last().clampNotice != null)

        vm.acknowledgeClampNotice()
        runCurrent()

        assertNull(
            "acknowledging must clear the notice for this and every subsequent frame",
            observed.last().clampNotice,
        )
        assertNull(
            "acknowledgement must reach the preferences layer, not just local UI state",
            preferences.clampNoticeFlow.first(),
        )

        job.cancel()
    }

    @Test
    fun `a user who was never clamped never sees a notice`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        assertNull(observed.last().clampNotice)

        job.cancel()
    }

    // ---- mapUiState: the pure oracle ----

    @Test
    fun `mapUiState reports approxPendingRamMb from the pending value, not the committed one`() {
        val state = SettingsViewModel.mapUiState(
            committedMinutes = 30,
            pendingMinutes = 45,
            pendingConfirmation = null,
        )
        assertTrue(
            "45 min should be roughly the documented ~86 MB, not 30 min's ~58 MB",
            state.retentionStepper.approxPendingRamMb in 75..95,
        )
    }

    @Test
    fun `mapUiState marks isDirty only when pending and committed disagree`() {
        val clean = SettingsViewModel.mapUiState(committedMinutes = 30, pendingMinutes = 30, pendingConfirmation = null)
        val dirty = SettingsViewModel.mapUiState(committedMinutes = 30, pendingMinutes = 35, pendingConfirmation = null)
        assertFalse(clean.retentionStepper.isDirty)
        assertTrue(dirty.retentionStepper.isDirty)
    }

    @Test
    fun `mapUiState computes memory budget across all three quality presets`() {
        val state = SettingsViewModel.mapUiState(
            committedMinutes = 30,
            pendingMinutes = 30,
            maxMemoryBytes = 256 * 1024 * 1024L,
            usedMemoryBytes = 20 * 1024 * 1024L,
        )
        assertEquals(3, state.qualityPresets.size)
        val voice = state.qualityPresets.first { it.preset == cc.machado.audioblackbox.audio.QualityPreset.VOICE }
        val hiFi = state.qualityPresets.first { it.preset == cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY }
        assertTrue(voice.maxRetentionMinutes >= hiFi.maxRetentionMinutes)
    }
}
