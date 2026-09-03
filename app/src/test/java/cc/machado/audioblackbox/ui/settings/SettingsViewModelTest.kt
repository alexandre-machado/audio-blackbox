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
        // Issue #298: there is no fixed AudioConfig.RETENTION_WINDOW_MAX_MINUTES any more, so this
        // test pins a device budget that lands the VOICE ceiling at exactly 45 -- see the maths in
        // DeviceMemoryBudgetTest for how maxHeapBytes/usedHeapBytes combine, and note
        // maxRetentionForPreset() subtracts the *committed* 40-min buffer's own bytes
        // (32_000 B/s * 40 * 60 = 76,800,000) from usedMemoryBytesProvider() before calling
        // DeviceMemoryBudget, so 86,800,000 here becomes an effective 10,000,000 non-buffer used.
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(40),
            maxMemoryBytesProvider = { 130_000_000L },
            usedMemoryBytesProvider = { 86_800_000L },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        runCurrent()
        assertEquals(45, observed.last().retentionStepper.pendingMinutes)
        assertFalse("45 is this pinned device's computed ceiling -- + must now be disabled", observed.last().retentionStepper.canIncrement)

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
    fun `committing while Recording persists and switches dynamically without stop or confirmation dialog`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        var stopCalls = 0
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset) },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.incrementPending()
        vm.incrementPending()
        vm.commitPendingRetentionWindow()
        runCurrent()

        assertEquals(0, stopCalls)
        assertEquals(listOf(45 to cc.machado.audioblackbox.audio.QualityPreset.VOICE), switchCalls)
        assertEquals(45, preferences.currentBufferDurationMinutes())
        assertNull(
            "in-place dynamic resize must never show the discard dialog (issue #223)",
            observed.last().retentionStepper.pendingConfirmationMinutes,
        )

        job.cancel()
    }

    @Test
    fun `committing both retention minutes and quality preset applies both in one atomic switch`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30, initialQualityPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        var stopCalls = 0
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            qualityPresetFlow = MutableStateFlow(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset) },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.decrementPending()
        vm.selectQualityPreset(cc.machado.audioblackbox.audio.QualityPreset.BALANCED)
        vm.commitPendingRetentionWindow()
        runCurrent()

        assertEquals(0, stopCalls)
        assertEquals(listOf(25 to cc.machado.audioblackbox.audio.QualityPreset.BALANCED), switchCalls)
        assertEquals(25, preferences.currentBufferDurationMinutes())
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.BALANCED, preferences.currentQualityPreset())
        assertNull(observed.last().retentionStepper.pendingConfirmationMinutes)

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

    @Test
    fun `mapUiState excludes active buffer memory from used heap so max retention stays stable`() {
        // 45 min buffer at 16kHz mono = 86,400,000 bytes (~82.4 MB)
        // With an active buffer, used heap is 100 MB (82.4 MB buffer + 17.6 MB runtime).
        // Without subtracting the buffer, remaining budget would be only (256*0.85 - 100) = 117.6 MB -> 9.6 min -> 5 min for HIFI.
        // With active buffer excluded, remaining budget is (256*0.85 - 17.6) = 200 MB -> 16.4 min -> 15 min for HIFI.
        val stateWithActiveBuffer = SettingsViewModel.mapUiState(
            committedMinutes = 45,
            pendingMinutes = 45,
            committedPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE,
            maxMemoryBytes = 256 * 1024 * 1024L,
            usedMemoryBytes = 100 * 1024 * 1024L,
        )
        val hifiWithBuffer = stateWithActiveBuffer.qualityPresets.first { it.preset == cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY }
        assertEquals(
            "High Fidelity max retention must not be penalized by active buffer size",
            15,
            hifiWithBuffer.maxRetentionMinutes,
        )
    }

    @Test
    fun `switching quality preset alone while Recording does not show discard dialog and switches immediately`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30, initialQualityPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        val switchedPresets = mutableListOf<cc.machado.audioblackbox.audio.QualityPreset>()
        var stopCalls = 0
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            qualityPresetFlow = MutableStateFlow(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onSwitchQualityPreset = { switchedPresets += it },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectQualityPreset(cc.machado.audioblackbox.audio.QualityPreset.BALANCED)
        vm.commitPendingRetentionWindow()
        runCurrent()

        assertEquals(0, stopCalls)
        assertEquals(listOf(cc.machado.audioblackbox.audio.QualityPreset.BALANCED), switchedPresets)
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.BALANCED, preferences.currentQualityPreset())
        assertNull("no discard confirmation needed for quality preset switch alone (issue #194)", observed.last().retentionStepper.pendingConfirmationMinutes)

        job.cancel()
    }

    @Test
    fun `mapUiState computes exact power and RAM telemetry metrics`() {
        val state = SettingsViewModel.mapUiState(
            committedMinutes = 30,
            pendingMinutes = 30,
            committedPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE,
            maxMemoryBytes = 256 * 1024 * 1024L,
            usedMemoryBytes = 32 * 1024 * 1024L,
            batteryStatus = cc.machado.audioblackbox.telemetry.BatteryStatus(
                percent = 88,
                isCharging = true,
                isIgnoringOptimizations = true,
            ),
        )

        val telemetry = state.telemetry
        assertEquals(88, telemetry.batteryPercent)
        assertTrue(telemetry.isCharging)
        assertTrue(telemetry.isIgnoringBatteryOptimizations)
        // 30 min at 16kHz mono (32,000 B/s) = 57,600,000 bytes = ~54.93 MB
        assertTrue("bufferMemoryMb should be ~54.9 MB", telemetry.bufferMemoryMb in 54.0..56.0)
        assertEquals(32.0, telemetry.usedHeapMb, 0.01)
        assertEquals(256.0, telemetry.maxHeapMb, 0.01)
        assertEquals("~1.0% – 1.5% / h", telemetry.estimatedDrainRate)
    }

    @Test
    fun `SettingsViewModel surfaces live battery and power telemetry through uiState`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 15)
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(15),
            qualityPresetFlow = MutableStateFlow(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            retentionWindowPreferences = preferences,
            batteryStatusProvider = {
                cc.machado.audioblackbox.telemetry.BatteryStatus(
                    percent = 74,
                    isCharging = false,
                    isIgnoringOptimizations = false,
                )
            },
            maxMemoryBytesProvider = { 192 * 1024 * 1024L },
            usedMemoryBytesProvider = { 24 * 1024 * 1024L },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        val lastState = observed.last()
        assertEquals(74, lastState.telemetry.batteryPercent)
        assertFalse(lastState.telemetry.isCharging)
        assertFalse(lastState.telemetry.isIgnoringBatteryOptimizations)
        // 15 min at 16kHz mono = 28,800,000 bytes = ~27.46 MB
        assertTrue(lastState.telemetry.bufferMemoryMb in 27.0..28.0)
        assertEquals(24.0, lastState.telemetry.usedHeapMb, 0.01)
        assertEquals(192.0, lastState.telemetry.maxHeapMb, 0.01)

        job.cancel()
    }
}
