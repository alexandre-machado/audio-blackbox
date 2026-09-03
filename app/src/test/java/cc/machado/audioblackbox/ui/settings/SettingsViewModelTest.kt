package cc.machado.audioblackbox.ui.settings

import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.test.advanceTimeBy
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
 * Issue #299 removed the Apply button: [SettingsViewModel.selectQualityPreset]/
 * [SettingsViewModel.incrementPending]/[SettingsViewModel.decrementPending] still only ever move a
 * local pending value directly, but every call also (re)schedules a single, shared, trailing-edge
 * debounce timer -- [SettingsViewModel.DEBOUNCE_MILLIS] after the last tap, whatever the pending
 * value is *at that moment* gets persisted and applied. Every test here asserts on the exact side
 * effects (whether `onRebuildEngine`/`onSwitchSettings`/`retentionWindowPreferences` were actually
 * invoked, and with what, and when relative to virtual time), not a re-derivation of the production
 * `when`/mapping.
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
        // Issue #317: restore the real, live-heap-derived provider -- a test that overrides this
        // companion-level `var` (see the real-switchSettings-path test below) must not leak a fixed
        // ceiling into whichever test runs next.
        cc.machado.audioblackbox.service.RecorderService.maxRetentionMinutesProvider = { preset ->
            cc.machado.audioblackbox.audio.DeviceMemoryBudget.maxRetentionMinutes(
                config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
                usedHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            )
        }
        cc.machado.audioblackbox.service.RecorderService.acknowledgeResizeRefusal()
        cc.machado.audioblackbox.service.RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            newPreset = cc.machado.audioblackbox.audio.QualityPreset.DEFAULT,
        )
        // Companion-level, process-lifetime (like RecorderService's own state above) -- must not
        // leak a refusal from one test into the next instance-construction test reads.
        SettingsViewModel.resetPersistedResizeErrorForTest()
    }

    // ---- Stepper: bounds and step arithmetic (unaffected by issue #299 -- these only ever touch
    // the local pending value, never the debounce timer) ----

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
    fun `stepping the pending value alone never persists or rebuilds anything before the debounce elapses`() = runTest(testDispatcher) {
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
        assertTrue("stepping alone, before the debounce elapses, must never rebuild", rebuildCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())

        job.cancel()
    }

    // ---- Issue #299: the shared trailing-edge debounce ----

    @Test
    fun `the debounce window elapsing commits the pending value`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        runCurrent()
        assertTrue("must not commit before the debounce window elapses", switchCalls.isEmpty())

        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf(35 to cc.machado.audioblackbox.audio.QualityPreset.VOICE), switchCalls)
        assertEquals(listOf(35 to cc.machado.audioblackbox.audio.QualityPreset.VOICE), rebuildCalls)
        assertEquals(35, preferences.currentBufferDurationMinutes())
        assertFalse(
            "once committed, the stepper must no longer show a pending/committed mismatch",
            observed.last().retentionStepper.isDirty,
        )

        job.cancel()
    }

    @Test
    fun `rapid taps before the debounce elapses collapse into exactly one commit`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
            onRebuildEngine = { _, _ -> true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        // A burst of taps spread 150ms apart -- each one inside the *previous* tap's own 500ms
        // debounce window, but with real virtual time passing between them (unlike a same-instant
        // burst, this is the only shape that can tell "shares one restarted timer" apart from "each
        // tap's own timer independently matures on schedule": if the first tap's timer were not
        // cancelled, it would fire 500ms after t=0 -- i.e. mid-burst, before the user has actually
        // stopped adjusting -- rather than 500ms after the *last* tap.
        vm.incrementPending() // t=0, pending 35
        runCurrent()
        advanceTimeBy(150) // t=150
        runCurrent()
        vm.incrementPending() // pending 40
        runCurrent()
        advanceTimeBy(150) // t=300
        runCurrent()
        vm.incrementPending() // pending 45
        runCurrent()
        advanceTimeBy(150) // t=450
        runCurrent()
        vm.decrementPending() // pending 40 -- last tap of the burst
        runCurrent()

        // t=949: 499ms after the last tap (t=450), and also 949ms after the very first one -- a
        // stale, uncancelled first timer (due at t=500) would already have fired somewhere in
        // [t=450, t=949], which is exactly what this window is designed to catch.
        advanceTimeBy(499)
        runCurrent()
        assertTrue(
            "no commit may land before a full 500ms of quiet since the *last* tap -- a stale, " +
                "uncancelled timer from an earlier tap firing mid-burst would defeat the entire " +
                "point of debouncing",
            switchCalls.isEmpty(),
        )

        // t=950: exactly 500ms after the last tap -- now it may commit.
        advanceTimeBy(1)
        runCurrent()

        assertEquals(
            "a burst of taps must collapse into exactly one commit, not one per tap",
            1,
            switchCalls.size,
        )
        assertEquals(40, switchCalls.single().first)

        job.cancel()
    }

    @Test
    fun `a preset tap immediately followed by stepper taps collapses into a single commit`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30, initialQualityPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            qualityPresetFlow = MutableStateFlow(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
            onRebuildEngine = { _, _ -> true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectQualityPreset(cc.machado.audioblackbox.audio.QualityPreset.BALANCED)
        vm.decrementPending()
        runCurrent()

        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(
            "a preset tap immediately followed by a stepper tap must share the same debounce timer",
            1,
            switchCalls.size,
        )
        assertEquals(25 to cc.machado.audioblackbox.audio.QualityPreset.BALANCED, switchCalls.single())

        job.cancel()
    }

    @Test
    fun `a tap arriving before the debounce fires cancels and restarts it, so only the last value commits`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
            onRebuildEngine = { _, _ -> true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        // t=0: first tap, pending -> 35, schedules a commit for t=500.
        vm.incrementPending()
        runCurrent()

        // t=499: still inside the first timer's window -- nothing has committed yet.
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertTrue("must not have committed yet", switchCalls.isEmpty())

        // t=499: a second tap arrives, pending -> 40. This must cancel the first timer (due at
        // t=500) and restart a fresh one due at t=999 -- if it merely started a second, uncancelled
        // timer instead, the stale one due at t=500 would fire one virtual millisecond from now,
        // committing the FIRST tap's value (35), not the user's actual last choice (40).
        vm.incrementPending()
        runCurrent()

        // t=999: enough virtual time has passed for the restarted timer to fire, and also enough
        // for a stale, uncancelled first timer (due at t=500) to have fired somewhere in this same
        // window -- this window is deliberately wide enough to catch either failure mode.
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(
            "a tap that lands before the timer fires must cancel and restart it, not run alongside " +
                "it -- exactly one commit, carrying the user's last chosen value (40), not the stale " +
                "first one (35)",
            listOf(40 to cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            switchCalls,
        )

        job.cancel()
    }

    @Test
    fun `committing the already-current value is a no-op`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        vm.decrementPending()
        runCurrent()
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertTrue("landing back on the current value must not commit or persist anything", switchCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())

        job.cancel()
    }

    // ---- `@rev` finding on PR #304: navigating away inside the debounce window must not drop the edit ----

    // `@rev` finding on PR #304, round 2: a direct `vm.onCleared()` call never actually cancels
    // `viewModelScope` the way real `androidx.lifecycle.ViewModel.clear()` does, so a test calling it
    // directly cannot tell "flushed on a scope that outlives the instance" apart from "flushed on
    // viewModelScope, which just happens to still be alive because nothing cancelled it in this
    // test". Routing teardown through a real `ViewModelStore` (pure JVM, no Robolectric/Android
    // framework needed) exercises the actual ordering the fix depends on: `ViewModelStore.clear()`
    // cancels `viewModelScope` *before* invoking `onCleared()`.
    @Test
    fun `clearing the ViewModel mid-debounce still flushes the pending commit`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
            onRebuildEngine = { _, _ -> true },
        )
        val store = ViewModelStore()
        store.put("settings", vm)

        // A tap lands, then -- well before the 500ms debounce elapses -- the user navigates away
        // (e.g. `NavHost` disposes the Settings destination), which is exactly what tears down this
        // ViewModel via a real `ViewModelStore.clear()`.
        vm.incrementPending()
        runCurrent()
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS / 2)
        runCurrent()

        store.clear()
        runCurrent()

        assertEquals(
            "an edit the UI already displayed as pending must still be committed after " +
                "navigating away mid-debounce, not silently dropped",
            listOf(35 to cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            switchCalls,
        )
        assertEquals(35, preferences.currentBufferDurationMinutes())
    }

    @Test
    fun `clearing the ViewModel with nothing dirty does not commit anything`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
        )
        val store = ViewModelStore()
        store.put("settings", vm)
        runCurrent()

        store.clear()
        runCurrent()

        assertTrue("nothing pending means nothing to flush", switchCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())
    }

    @Test
    fun `a refusal flushed after the ViewModel is cleared surfaces on the next SettingsViewModel instead of being lost`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { _, _ -> false },
        )
        val store = ViewModelStore()
        store.put("settings", vm)

        vm.incrementPending()
        runCurrent()
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS / 2)
        runCurrent()

        // Navigate away mid-debounce (as above), but this time the flushed commit is refused --
        // there is nobody left to show `resizeError` to on `vm` itself.
        store.clear()
        runCurrent()

        assertEquals(30, preferences.currentBufferDurationMinutes())

        // The user reopens Settings: a fresh instance must surface the refusal the dead one
        // couldn't -- the entire point of stashing it on the companion-owned marker.
        val reopened = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
        )
        assertEquals(
            "a refusal that landed after the screen was gone must surface on the next instance",
            35,
            reopened.uiState.value.resizeError?.requestedMinutes,
        )

        // And it must not resurface a third time -- it was consumed by `reopened` above.
        val reopenedAgain = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
        )
        assertNull(
            "the same refusal must not surface a second time once a fresh instance already consumed it",
            reopenedAgain.uiState.value.resizeError,
        )
    }

    // ---- Issue #272 refusal path (surfaced through issue #299's only remaining feedback channel) ----

    @Test
    fun `a refused commit reverts the displayed value to what is running and surfaces resizeError`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { _, _ -> false },
            onRebuildEngine = { minutes, preset -> rebuildCalls += (minutes to preset); true },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending()
        runCurrent()
        assertEquals(35, observed.last().retentionStepper.pendingMinutes)

        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertTrue(
            "a refusal must never persist or rebuild -- the previous setting stays in force",
            rebuildCalls.isEmpty(),
        )
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertEquals(
            "with no Apply button, the pending display must revert to what is actually running",
            30,
            observed.last().retentionStepper.pendingMinutes,
        )
        assertFalse(observed.last().retentionStepper.isDirty)
        assertEquals(
            "the refusal must surface the real requested minutes -- the only feedback channel left",
            35,
            observed.last().resizeError?.requestedMinutes,
        )

        job.cancel()
    }

    // ---- Issue #317: a live ceiling that shrinks between the offer and the commit must never crash ----

    // The exact production shape (issue #317's root cause): the stepper's own bound
    // (`maxRetentionForPreset`, driven by this ViewModel's own maxMemoryBytesProvider/
    // usedMemoryBytesProvider) and `RecorderService.switchSettings`'s live check
    // (`RecorderService.maxRetentionMinutesProvider`) are two *independent* samples of the same
    // non-stationary memory budget. This test does not fake `onSwitchSettings` at all -- it drives
    // the real, default-wired `RecorderService.switchSettings` companion function, through the real
    // `commitPending` debounce path, with a `RecorderService.maxRetentionMinutesProvider` that
    // genuinely disagrees with what the stepper's own (much larger) ceiling let the user reach. A
    // fixture hard-coding one stable ceiling everywhere cannot reproduce this -- see issue #317.
    @Test
    fun `a live ceiling that has shrunk by commit time never crashes -- the real switchSettings path refuses (issue 317)`() = runTest(testDispatcher) {
        cc.machado.audioblackbox.service.RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = 90,
            newPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE,
        )
        // The live ceiling RecorderService.switchSettings will actually sample when the debounced
        // commit runs -- lower than the 90 already committed, and far lower than this ViewModel's
        // own (deliberately huge) offer-time ceiling below.
        cc.machado.audioblackbox.service.RecorderService.maxRetentionMinutesProvider = { 95 }

        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 90, maxRetentionMinutesProvider = { 95 })
        val vm = SettingsViewModel(
            captureState = cc.machado.audioblackbox.service.RecorderService.captureState,
            capacityMinutesFlow = cc.machado.audioblackbox.service.RecorderService.bufferDurationMinutesFlow,
            qualityPresetFlow = cc.machado.audioblackbox.service.RecorderService.qualityPresetFlow,
            retentionWindowPreferences = preferences,
            onRebuildEngine = { m, p -> cc.machado.audioblackbox.service.RecorderService.rebuildEngineIfIdle(m, p) },
            // Deliberately far above RecorderService's live 95 -- this is what lets the stepper
            // offer 100 in the first place, exactly like the captured "was 100" production crashes.
            maxMemoryBytesProvider = { Long.MAX_VALUE / 4 },
            usedMemoryBytesProvider = { 0L },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.incrementPending() // 90 -> 95
        vm.incrementPending() // 95 -> 100 -- stepper's own huge ceiling lets this through
        runCurrent()
        assertEquals(100, observed.last().retentionStepper.pendingMinutes)

        // The debounced commit calls the real RecorderService.switchSettings(100, VOICE), which
        // samples its own live ceiling (95) -- lower than the offered 100. Before issue #317 this
        // `require()`d and crashed the process; it must now refuse gracefully instead.
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(
            "the previous, still-running 90 min setting must stay in force, never crash",
            90,
            preferences.currentBufferDurationMinutes(),
        )
        assertEquals(
            "with no Apply button, the pending display must revert to what is actually running",
            90,
            observed.last().retentionStepper.pendingMinutes,
        )
        assertEquals(
            "the refusal must surface the real requested minutes -- the only feedback channel left",
            100,
            observed.last().resizeError?.requestedMinutes,
        )

        job.cancel()
    }

    // ---- Issue #193: Quality Preset selection and bounds ----

    @Test
    fun `selecting a quality preset marks state as dirty and adjusts max selectable retention`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val switchCalls = mutableListOf<Pair<Int, cc.machado.audioblackbox.audio.QualityPreset>>()
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            qualityPresetFlow = MutableStateFlow(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            retentionWindowPreferences = preferences,
            onSwitchSettings = { minutes, preset -> switchCalls += (minutes to preset); true },
            onRebuildEngine = { _, _ -> true },
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

        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(1, switchCalls.size)
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, switchCalls.first().second)
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
        )
        assertTrue(
            "45 min should be roughly the documented ~86 MB, not 30 min's ~58 MB",
            state.retentionStepper.approxPendingRamMb in 75..95,
        )
    }

    @Test
    fun `mapUiState marks isDirty only when pending and committed disagree`() {
        val clean = SettingsViewModel.mapUiState(committedMinutes = 30, pendingMinutes = 30)
        val dirty = SettingsViewModel.mapUiState(committedMinutes = 30, pendingMinutes = 35)
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
    fun `switching quality preset alone while Recording commits after the debounce and switches immediately`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30, initialQualityPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        val switchedPresets = mutableListOf<cc.machado.audioblackbox.audio.QualityPreset>()
        var stopCalls = 0
        val vm = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            qualityPresetFlow = MutableStateFlow(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onSwitchSettings = { _, _ -> true },
            onRebuildEngine = { _, _ -> true },
            onSwitchQualityPreset = { switchedPresets += it },
        )
        val observed = mutableListOf<SettingsUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectQualityPreset(cc.machado.audioblackbox.audio.QualityPreset.BALANCED)
        runCurrent()
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(0, stopCalls)
        assertEquals(listOf(cc.machado.audioblackbox.audio.QualityPreset.BALANCED), switchedPresets)
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.BALANCED, preferences.currentQualityPreset())

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
