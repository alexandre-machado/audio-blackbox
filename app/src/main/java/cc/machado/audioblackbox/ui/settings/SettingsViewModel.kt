package cc.machado.audioblackbox.ui.settings

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.DeviceMemoryBudget
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.audio.SwitchConfigResult
import cc.machado.audioblackbox.service.RecorderService
import cc.machado.audioblackbox.settings.ClampNotice
import cc.machado.audioblackbox.settings.InMemoryRetentionWindowPreferences
import cc.machado.audioblackbox.settings.RetentionWindowPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the settings screen state: retention-window stepper (issue #73) and quality preset selector (issue #193).
 *
 * ## Why every tap persists on its own, debounced (issue #299)
 * There used to be an Apply button here, justified by a claim that changing the retention window or
 * preset discards whatever audio is currently buffered. That stopped being true: in-place buffer
 * resizing (issue #223) and quality-preset switching (issue #194) both preserve buffered audio
 * across the boundary without stopping capture -- see [commitPending]'s doc, and
 * [RecorderService.switchSettings]'s. The Apply button, and the discard-confirmation dialog it
 * guarded, survived only as dead code justified by a doc comment nothing in the running code any
 * longer backed.
 *
 * [incrementPending]/[decrementPending]/[selectQualityPreset] still only ever move a local pending
 * value directly -- that is what keeps the stepper tracking taps at tap speed -- but each call also
 * (re)schedules a single, shared, trailing-edge debounce timer ([scheduleDebouncedCommit]).
 * [commitPending] is what actually persists and switches the live engine, exactly once per settled
 * burst of taps: a fresh tap cancels and restarts the same timer rather than starting a second one,
 * so a preset tap immediately followed by a run of stepper taps collapses into one commit, one
 * resize, not one per tap (each is a real reallocation of up to hundreds of MB). Because [commitPending]
 * always reads the *current* pending value at the moment it actually runs -- never a value captured
 * when the timer was scheduled -- a tap that arrives before the timer fires is naturally the one
 * that ends up committed, with no separate "torn state" handling required.
 *
 * If the engine refuses the resulting resize (issue #272), the previous, still-running setting stays
 * in force: [_resizeError] surfaces the real numbers, and the pending value is reset back to the
 * committed one so the stepper's displayed value matches what is actually running. With no Apply
 * button left, that reversion plus [_resizeError] is the *only* feedback channel a refused change
 * has -- see [commitPending].
 *
 * ## Navigating away inside the debounce window (`@rev` finding on PR #304)
 * [onCleared] flushes any still-dirty pending edit immediately, on [commitFlushScope] -- a
 * companion-owned scope that outlives this instance, not [viewModelScope] (which
 * `androidx.lifecycle.ViewModel.clear()` cancels before/around `onCleared()` -- launching the flush
 * on the dying scope would just get the flush itself cancelled, silently dropping the exact edit
 * this exists to save). Without this, a tap followed by navigating off the Settings screen before
 * [DEBOUNCE_MILLIS] elapses would cancel [commitJob] mid-flight and lose an edit the UI had already
 * shown as pending -- precisely the "silently-ignored tap" failure mode this whole redesign exists
 * to rule out, just triggered by navigation instead of a second tap. A flush that is then refused
 * (issue #272) is surfaced the next time a [SettingsViewModel] is constructed rather than lost --
 * see [onCleared]'s own doc.
 */
class SettingsViewModel(
    private val captureState: StateFlow<CaptureState> = RecorderService.captureState,
    private val capacityMinutesFlow: StateFlow<Int> = RecorderService.bufferDurationMinutesFlow,
    private val qualityPresetFlow: StateFlow<QualityPreset> = RecorderService.qualityPresetFlow,
    private val onStopEngine: () -> Unit = {},
    private val retentionWindowPreferences: RetentionWindowPreferences = InMemoryRetentionWindowPreferences(),
    // Issue #299: the single injectable seam behind commitPending's actual switch attempt --
    // defaults to the real RecorderService.switchSettings (issue #272's refusal-aware
    // dynamic-switch entry point) in production, and is what a test substitutes a deterministic
    // fake for to drive the refusal-reverts-the-displayed-value path without needing to force a
    // real JVM heap over its actual ceiling.
    private val onSwitchSettings: (minutes: Int, preset: QualityPreset) -> Boolean = { m, p -> RecorderService.switchSettings(m, p) },
    private val onRebuildEngine: (minutes: Int, preset: QualityPreset) -> Boolean = { m, p -> RecorderService.rebuildEngineIfIdle(m, p) },
    private val onSwitchQualityPreset: (QualityPreset) -> Unit = { RecorderService.switchQualityPreset(it) },
    private val maxMemoryBytesProvider: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val usedMemoryBytesProvider: () -> Long = { Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() },
    // Issue #298: DeviceMemoryBudget.maxRetentionMinutes's second, independent limit --
    // ActivityManager.MemoryInfo.availMem in production (wired by MainActivity, the same pattern as
    // batteryStatusProvider below), null by default so a JVM test that doesn't care about this term
    // gets the same "heap-only" behavior every existing test already assumed.
    private val availableSystemBytesProvider: () -> Long? = { null },
    private val batteryStatusProvider: () -> cc.machado.audioblackbox.telemetry.BatteryStatus = {
        cc.machado.audioblackbox.telemetry.BatteryStatus(percent = 100, isCharging = false, isIgnoringOptimizations = true)
    },
) : ViewModel() {

    private val _pendingMinutes = MutableStateFlow<Int?>(null)
    private val _pendingPreset = MutableStateFlow<QualityPreset?>(null)

    // Non-null exactly while there is an unacknowledged "your settings change could not be
    // applied" refusal (issue #272) -- a real, user-visible signal for a resize the engine
    // refused rather than crashed on, per AGENTS.md §5 "never fake a signal in the UI". Cleared by
    // [dismissResizeError] and also whenever a new commit is attempted. With no Apply button
    // (issue #299) this is the only feedback channel a refused change has -- see [commitPending].
    // Seeded from _persistedResizeError, not always null (`@rev` finding on PR #304, round 2): a
    // refusal flushed by a *previous* instance's onCleared() after its screen was already gone
    // (nobody around to see it then) surfaces here instead, the moment a fresh SettingsViewModel is
    // constructed -- consumed exactly once, so a later instance doesn't see the same notice again.
    // Issue #306: the read+clear below is a compound, non-atomic operation over a
    // *companion-owned* (process-lifetime) value that is safe only because construction always
    // happens on the main thread -- see [consumePersistedResizeError]'s doc for why that
    // requirement is now explicit rather than merely assumed.
    private val _resizeError = MutableStateFlow(consumePersistedResizeError())

    // The single, shared trailing-edge debounce timer behind every pending edit (issue #299): a
    // fresh tap on either control cancels whatever is currently scheduled and starts one new
    // DEBOUNCE_MILLIS timer, so a preset tap immediately followed by a run of stepper taps
    // collapses into exactly one [commitPending] call, not one per tap. See [scheduleDebouncedCommit].
    private var commitJob: Job? = null

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(capacityMinutesFlow, qualityPresetFlow, ::Pair),
        combine(_pendingMinutes, _pendingPreset, ::Pair),
        retentionWindowPreferences.clampNoticeFlow,
        _resizeError,
    ) { (committedMins, committedPreset), (pendingMins, pendingPreset), clampNotice, resizeError ->
        val effectivePendingMins = pendingMins ?: committedMins
        val effectivePendingPreset = pendingPreset ?: committedPreset
        mapUiState(
            committedMinutes = committedMins,
            pendingMinutes = effectivePendingMins,
            committedPreset = committedPreset,
            pendingPreset = effectivePendingPreset,
            clampNotice = clampNotice,
            resizeError = resizeError,
            maxMemoryBytes = maxMemoryBytesProvider(),
            usedMemoryBytes = usedMemoryBytesProvider(),
            availableSystemBytes = availableSystemBytesProvider(),
            batteryStatus = batteryStatusProvider(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            committedMinutes = capacityMinutesFlow.value,
            pendingMinutes = _pendingMinutes.value ?: capacityMinutesFlow.value,
            committedPreset = qualityPresetFlow.value,
            pendingPreset = _pendingPreset.value ?: qualityPresetFlow.value,
            clampNotice = null,
            // `@rev` finding on PR #304, round 2: was hardcoded `null` -- harmless while `_resizeError`
            // could only ever start `null` too, but that stopped being true once construction can
            // seed it from `_persistedResizeError` (see above). `stateIn`'s `initialValue` is a
            // one-time snapshot read *before* `uiState` has any subscriber to drive the `combine`
            // flow past it (`WhileSubscribed` keeps that flow cold until one exists), so a caller
            // reading `uiState.value` synchronously right after construction -- exactly what a
            // reopened Settings screen's very first frame does -- would otherwise see this
            // hardcoded `null` instead of the just-seeded refusal.
            resizeError = _resizeError.value,
            maxMemoryBytes = maxMemoryBytesProvider(),
            usedMemoryBytes = usedMemoryBytesProvider(),
            availableSystemBytes = availableSystemBytesProvider(),
            batteryStatus = batteryStatusProvider(),
        ),
    )

    private fun maxRetentionForPreset(preset: QualityPreset): Int {
        val committedMinutes = capacityMinutesFlow.value
        val committedPreset = qualityPresetFlow.value
        val currentBufferBytes = committedPreset.config(bufferDurationMinutes = committedMinutes).totalBufferBytes
        val nonBufferUsedBytes = (usedMemoryBytesProvider() - currentBufferBytes).coerceAtLeast(0L)
        return DeviceMemoryBudget.maxRetentionMinutes(
            config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
            maxHeapBytes = maxMemoryBytesProvider(),
            usedHeapBytes = nonBufferUsedBytes,
            availableSystemBytes = availableSystemBytesProvider(),
        )
    }

    private fun currentPendingMinutes(): Int = _pendingMinutes.value ?: capacityMinutesFlow.value
    private fun currentPendingPreset(): QualityPreset = _pendingPreset.value ?: qualityPresetFlow.value

    fun selectQualityPreset(preset: QualityPreset) {
        _pendingPreset.value = preset
        val maxForPreset = maxRetentionForPreset(preset)
        val mins = currentPendingMinutes()
        if (mins > maxForPreset) {
            _pendingMinutes.value = maxForPreset
        }
        scheduleDebouncedCommit()
    }

    fun incrementPending() {
        val currentPreset = currentPendingPreset()
        val maxForPreset = maxRetentionForPreset(currentPreset)
        _pendingMinutes.value = (currentPendingMinutes() + AudioConfig.RETENTION_WINDOW_STEP_MINUTES)
            .coerceAtMost(maxForPreset)
        scheduleDebouncedCommit()
    }

    fun decrementPending() {
        _pendingMinutes.value = (currentPendingMinutes() - AudioConfig.RETENTION_WINDOW_STEP_MINUTES)
            .coerceAtLeast(AudioConfig.RETENTION_WINDOW_MIN_MINUTES)
        scheduleDebouncedCommit()
    }

    fun resetPending() {
        _pendingMinutes.value = null
        _pendingPreset.value = null
    }

    /** `@rev` finding on PR #304: navigating away from Settings inside the [DEBOUNCE_MILLIS] window
     * used to lose the pending edit outright -- `viewModelScope` (and [commitJob] with it) is
     * cancelled here, before the debounced [commitPending] ever got to run, with zero feedback: no
     * `resizeError`, no reverted display, because nothing is mounted to show either. That is exactly
     * the failure mode issue #299 was written to design against, just reached via navigation rather
     * than a tap race.
     *
     * The fix: flush a still-dirty pending edit synchronously on [commitFlushScope] -- a scope that
     * outlives this instance -- rather than on the [viewModelScope] that is dying right now. The
     * flushed commit still goes through the exact same [commitPending] (same success/refusal
     * handling, same "never persist a value the engine actually refused" invariant from issue #272)
     * as a normal, in-session commit.
     *
     * ## The one case this does not fully close in-session: a refusal after the screen is already gone
     * If the flushed commit is refused, [commitPending] still sets [_resizeError] (nobody is
     * mounted to read it any more) but *also* stashes it on [_persistedResizeError] -- the
     * companion-owned marker the next [SettingsViewModel] instance's `init` seeds its own
     * [_resizeError] from, so reopening Settings is what actually surfaces a refusal that landed
     * after the screen was gone (`@rev` finding on PR #304, round 2: the seam already exists to make
     * this genuinely testable via the `onSwitchSettings` fake, so it is implemented rather than left
     * as an unverifiable claim). The property issue #272 actually protects -- the persisted
     * preference and the live engine's real capacity can never diverge -- held even before this,
     * since [commitPending] only ever writes the preference on success either way; this closes the
     * remaining *notification* gap on top of that.
     *
     * `override`, not `public override`: `androidx.lifecycle.ViewModelStore.clear()` is the real, public
     * `androidx.lifecycle` entry point that invokes this without needing any visibility widening --
     * `SettingsViewModelTest` drives teardown through a real `ViewModelStore` (see
     * `clearingTheViewModelThroughViewModelStore` there) precisely so the test exercises the actual
     * "`viewModelScope` is cancelled before `onCleared()` runs" ordering this fix depends on, the
     * same reason a direct `vm.onCleared()` call could not have caught the original defect (`@rev`
     * verified: reverting [commitFlushScope] back to [viewModelScope] here made that direct-call test
     * keep passing, because a direct call never actually cancels `viewModelScope`). Leaving this
     * `protected` (`@sec` finding) also closes off any caller other than the framework cancelling a
     * live debounce or forcing a premature commit out of sequence. */
    override fun onCleared() {
        commitJob?.cancel()
        val minutes = currentPendingMinutes()
        val preset = currentPendingPreset()
        if (minutes != capacityMinutesFlow.value || preset != qualityPresetFlow.value) {
            commitFlushScope.launch { commitPending(afterClear = true) }
        }
    }

    /** The single shared trailing-edge debounce timer (issue #299): cancels whatever commit is
     * currently scheduled -- including one still waiting out its [DEBOUNCE_MILLIS] delay -- and
     * starts a fresh one. A tap that lands before the previous timer fired therefore never lets a
     * stale commit run; only the *last* call in a burst ever gets far enough to actually delay and
     * fire, which is exactly what makes a preset tap followed by a run of stepper taps collapse
     * into the single [commitPending] call at the bottom of the burst, not one per tap. */
    private fun scheduleDebouncedCommit() {
        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            delay(DEBOUNCE_MILLIS)
            commitPending()
        }
    }

    /** Persists and switches the live engine to whatever [_pendingMinutes]/[_pendingPreset]
     * currently hold -- always the *current* value at the moment this actually runs, never a value
     * captured back when the debounce timer was scheduled, so the last tap in a burst is always the
     * one that ends up committed.
     *
     * In-place buffer resizing (issue #223) and quality preset switching (issue #194) both preserve
     * buffered audio across this boundary without stopping capture -- this is why issue #299 could
     * remove the Apply button and its discard-confirmation dialog: neither one guards anything real
     * any more.
     *
     * Issue #272: only commits the new setting -- persisted preference and the committed
     * StateFlows [RecorderService.switchSettings] updates on success -- if the live buffer's resize
     * actually applied. Persisting unconditionally would leave the stored preference and the
     * engine's actual capacity out of sync whenever a resize was refused, and would silently hide
     * the refusal from the user on top of that. On a refusal, [resetPending] reverts the displayed
     * (pending) value back to the still-active committed one -- with no Apply button, this revert
     * plus [_resizeError] is the *only* signal the user gets that their tap did not take effect. */
    private suspend fun commitPending(afterClear: Boolean = false) {
        val minutes = currentPendingMinutes()
        val preset = currentPendingPreset()
        val isDirty = minutes != capacityMinutesFlow.value || preset != qualityPresetFlow.value
        if (!isDirty) {
            resetPending()
            return
        }
        _resizeError.value = null
        val applied = onSwitchSettings(minutes, preset)
        if (applied) {
            retentionWindowPreferences.setBufferDurationMinutes(minutes)
            retentionWindowPreferences.setQualityPreset(preset)
            onRebuildEngine(minutes, preset)
            onSwitchQualityPreset(preset)
            resetPending()
        } else {
            val refusal = RecorderService.resizeRefusalFlow.value
            RecorderService.acknowledgeResizeRefusal()
            val info = describeRefusal(refusal, minutes)
            _resizeError.value = info
            if (afterClear) {
                // `@rev` finding on PR #304 (round 2): nobody is mounted to read `_resizeError`
                // above -- this instance is already dying/dead. Record it on the companion-owned
                // marker instead, so the *next* `SettingsViewModel` (the user reopening Settings)
                // surfaces it -- see the constructor's `init` seeding below. Only stashed on the
                // flushed-after-clear path: an in-session refusal is already shown right here via
                // `_resizeError`, and does not need (or want) to resurface again on the next
                // instance after the user dismisses it.
                // Issue #306: this write and [consumePersistedResizeError]'s read+clear are the two
                // ends of the same unenforced main-thread invariant -- see that function's doc.
                stashPersistedResizeError(info)
            }
            // Revert the displayed value to what is actually running (issue #299 requirement:
            // with no Apply button, this is the only way the stepper does not keep showing a
            // value that never took).
            resetPending()
        }
    }

    fun dismissResizeError() {
        _resizeError.value = null
    }

    fun acknowledgeClampNotice() {
        viewModelScope.launch {
            retentionWindowPreferences.acknowledgeClampNotice()
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val BYTES_PER_MB = 1_000_000L

        /** `@rev` finding on PR #304 (round 2): a companion-owned scope, deliberately *not* parented
         * to any one [SettingsViewModel] instance's [viewModelScope] -- it must survive exactly the
         * event ([onCleared]) that cancels that scope. Mirrors the existing pattern one level down in
         * this same codebase, [RecorderService]'s own companion-owned `forwardingScope`/`serviceScope`
         * (process-lifetime, outliving any one Service/ViewModel instance for the same structural
         * reason). Does not leak: every use here ([onCleared]) launches at most one short-lived,
         * already-bounded coroutine (a single [commitPending] call, no loop, no retry) per cleared
         * instance that had a genuinely dirty pending edit -- nothing keeps this scope's own
         * [SupervisorJob] artificially alive, and a completed child coroutine is not retained.
         *
         * `by lazy`, not a plain eager property: a companion object has exactly one combined static
         * initializer for *all* its members, so an eager `CoroutineScope(... + Dispatchers.Main.immediate)`
         * here ran the moment *anything* on this companion was first touched -- including
         * `describeRefusal`/`mapUiState`/`DEBOUNCE_MILLIS`, called by tests (`SettingsResizeErrorTest`)
         * that never call `Dispatchers.setMain()`. `Dispatchers.Main.immediate` throws with no
         * platform Main dispatcher installed and no test dispatcher set, which failed this
         * companion's `<clinit>` -- and per JVM semantics a class that fails static init is
         * permanently poisoned for the rest of that classloader/test JVM, so *every* later reference
         * to `SettingsViewModel` in the same run failed with `NoClassDefFoundError`, including every
         * test in `SettingsViewModelTest` itself, regardless of its own `setMain()` call (confirmed:
         * this is exactly what broke CI run `33781119163`, 25/469 tests, all traced to this one
         * line). `by lazy` defers resolving `Dispatchers.Main.immediate` until [commitFlushScope] is
         * actually read -- only from [onCleared]'s flush branch -- by which point every real caller
         * (production: Android's real Main looper; tests: `SettingsViewModelTest`'s own `@Before`)
         * has already installed one.
         *
         * Default (`SYNCHRONIZED`) mode, not `LazyThreadSafetyMode.NONE` (issue #306, filed by
         * `@sec` off PR #304): this property is companion-owned -- one instance shared by every
         * `SettingsViewModel` across the process, not per-instance state -- and its only actual
         * caller today ([onCleared]) is guaranteed main-thread by the `androidx.lifecycle`
         * contract, but nothing in the *type* enforced that; `NONE` explicitly opts out of the
         * synchronisation `by lazy` would otherwise give for free. A double-checked-locking-style
         * initialization race on first access (e.g. a future caller added off the main thread)
         * under `NONE` can publish a torn view of the backing `CoroutineScope` or run the
         * initializer block twice, leaking a `SupervisorJob`. The default `SYNCHRONIZED` mode
         * closes that off unconditionally rather than merely documenting the assumption: the
         * access pattern here is once per cleared instance, not a hot loop, so a monitor on this
         * path costs nothing that matters, and it makes correctness independent of which thread
         * ever ends up calling [onCleared] -- the same "make the divergence impossible" standard
         * PR #300 set for `reconcileRetentionCeiling`/`rebuildEngineIfIdle`. */
        private val commitFlushScope: CoroutineScope by lazy {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }

        // `@rev` finding on PR #304 (round 2): a refusal flushed by `onCleared` after the screen is
        // already gone has nobody to show `_resizeError` to -- this is the companion-owned,
        // process-lifetime marker that lets the *next* `SettingsViewModel` instance (the user
        // reopening Settings) surface it instead of it being lost for good. Set by `commitPending`'s
        // refusal branch unconditionally (in-session or flushed, the same as `_resizeError` itself);
        // consumed exactly once, by whichever instance's `init` reads it next -- see [SettingsViewModel]'s
        // constructor.
        //
        // Issue #306: both ends of this write/consume pair are only safe because they happen to run
        // on the main thread today ([stashPersistedResizeError] from `commitPending`, itself always
        // launched on a `Dispatchers.Main.immediate`-confined scope; [consumePersistedResizeError]
        // from a `SettingsViewModel` constructor, which `androidx.lifecycle`/`ViewModelProvider`
        // guarantees runs on main) -- nothing in the type of a raw `MutableStateFlow<T>.value`
        // enforces that. Unlike [commitFlushScope] above, there is no `by lazy` thread-safety mode
        // to flip here: the hazard is not a torn *value* (`StateFlow.value` is already a plain
        // atomic/volatile read-modify), it is the read-then-clear pair being treated as a single
        // hand-off. A `SYNCHRONIZED` wrapper would not fix that -- it would only serialize two
        // instances racing to "consume first", not restore the ordering the hand-off actually
        // depends on. `@MainThread`-annotated accessor functions make the requirement explicit and
        // lint-checked (`lintDebug`, part of this repo's mandatory validation gate) instead of an
        // implicit assumption baked into a bare field read -- see [stashPersistedResizeError] and
        // [consumePersistedResizeError]. A real runtime assertion (`Looper.getMainLooper()`) was
        // considered and rejected: this repo's Tier 0 JVM unit tests (`testDebugUnitTest`, see
        // AGENTS.md §6) run on a bare `android.jar` stub with no Robolectric and no real Looper --
        // every `SettingsViewModelTest`/`SettingsResizeErrorTest` case that constructs a
        // `SettingsViewModel` or drives a refusal would throw "not mocked" immediately, and adding
        // Robolectric is outside this issue's affected surface.
        private val _persistedResizeError = MutableStateFlow<ResizeErrorInfo?>(null)

        /** Issue #306: the write half of the [_persistedResizeError] hand-off -- see that field's
         * doc for why this is `@MainThread` rather than a runtime-enforced check. Called only from
         * [commitPending]'s refusal branch, itself always running on a `Dispatchers.Main.immediate`-
         * confined scope ([viewModelScope] or [commitFlushScope]). */
        @MainThread
        private fun stashPersistedResizeError(info: ResizeErrorInfo) {
            _persistedResizeError.value = info
        }

        /** Issue #306: the read+clear half of the [_persistedResizeError] hand-off -- see that
         * field's doc for why this is `@MainThread` rather than a runtime-enforced check. Called
         * only from a [SettingsViewModel] constructor, which `androidx.lifecycle.ViewModelProvider`
         * guarantees runs on the main thread. Consumes the stashed refusal at most once: the second
         * caller to run this after a stash sees `null`, exactly like the field read it replaces. */
        @MainThread
        private fun consumePersistedResizeError(): ResizeErrorInfo? =
            _persistedResizeError.value?.also { _persistedResizeError.value = null }

        /** Test-only reset for [_persistedResizeError], so one test's refusal cannot leak into the
         * next -- this is a companion-level (process-lifetime, by design) field, the same reason
         * [cc.machado.audioblackbox.service.RecorderService]'s own companion state needs an explicit
         * reset in test `tearDown()`. */
        fun resetPersistedResizeErrorForTest() {
            _persistedResizeError.value = null
        }

        /** Issue #299: the shared trailing-edge debounce window behind every pending edit -- see
         * [scheduleDebouncedCommit]. */
        const val DEBOUNCE_MILLIS = 500L

        /** Real, specific data for a refused resize (issue #272) -- states the actual numbers
         * involved rather than a generic "something went wrong", per AGENTS.md §5. Returns data,
         * not a formatted message: [SettingsScreen] renders the actual wording through
         * `strings.xml` (`R.string.settings_resize_error_body`/`_no_mb`) so it gets a real pt-BR
         * translation instead of a hardcoded Kotlin literal. */
        fun describeRefusal(refusal: SwitchConfigResult.BufferResizeRefused?, requestedMinutes: Int): ResizeErrorInfo {
            val outcome = refusal?.outcome
            val requestedMb = outcome?.let { (it.requestedCapacityBytes / BYTES_PER_MB).toInt() }
            return ResizeErrorInfo(requestedMinutes = requestedMinutes, requestedMb = requestedMb)
        }

        /** The single state-mapping oracle for this screen: committed capacity + local pending
         * value -> the exact [SettingsUiState] the screen renders. */
        fun mapUiState(
            committedMinutes: Int,
            pendingMinutes: Int,
            committedPreset: QualityPreset = QualityPreset.DEFAULT,
            pendingPreset: QualityPreset = committedPreset,
            clampNotice: ClampNotice? = null,
            resizeError: ResizeErrorInfo? = null,
            maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
            usedMemoryBytes: Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            availableSystemBytes: Long? = null,
            batteryStatus: cc.machado.audioblackbox.telemetry.BatteryStatus = cc.machado.audioblackbox.telemetry.BatteryStatus(),
        ): SettingsUiState {
            val currentBufferBytes = committedPreset.config(bufferDurationMinutes = committedMinutes).totalBufferBytes
            val nonBufferUsedBytes = (usedMemoryBytes - currentBufferBytes).coerceAtLeast(0L)

            val presetOptions = QualityPreset.entries.map { preset ->
                val maxRetention = DeviceMemoryBudget.maxRetentionMinutes(
                    config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
                    maxHeapBytes = maxMemoryBytes,
                    usedHeapBytes = nonBufferUsedBytes,
                    availableSystemBytes = availableSystemBytes,
                )
                QualityPresetOption(
                    preset = preset,
                    maxRetentionMinutes = maxRetention,
                    isSelected = preset == pendingPreset,
                )
            }
            // No static fallback constant any more (issue #298): every preset always has a
            // computed entry in presetOptions (QualityPreset.entries is exhaustive), so
            // firstOrNull{} here can only ever be null if pendingPreset itself is somehow not a
            // real QualityPreset entry -- which the type system already rules out.
            val currentPresetMax = presetOptions.first { it.preset == pendingPreset }.maxRetentionMinutes
            val clampedPendingMinutes = pendingMinutes.coerceAtMost(currentPresetMax)

            val stepper = RetentionStepperUiState(
                pendingMinutes = clampedPendingMinutes,
                committedMinutes = committedMinutes,
                approxPendingRamMb = (
                    pendingPreset.config(bufferDurationMinutes = clampedPendingMinutes).totalBufferBytes / BYTES_PER_MB
                ).toInt(),
                canDecrement = clampedPendingMinutes > AudioConfig.RETENTION_WINDOW_MIN_MINUTES,
                canIncrement = clampedPendingMinutes < currentPresetMax,
                isDirty = clampedPendingMinutes != committedMinutes || pendingPreset != committedPreset,
                maxSelectableMinutes = currentPresetMax,
            )

            val bufferBytes = committedPreset.config(bufferDurationMinutes = committedMinutes).totalBufferBytes
            val bufferMb = bufferBytes / (1024.0 * 1024.0)
            val usedHeapMb = usedMemoryBytes / (1024.0 * 1024.0)
            val maxHeapMb = maxMemoryBytes / (1024.0 * 1024.0)

            val telemetry = PowerTelemetryUiState(
                batteryPercent = batteryStatus.percent,
                isCharging = batteryStatus.isCharging,
                isIgnoringBatteryOptimizations = batteryStatus.isIgnoringOptimizations,
                bufferMemoryMb = bufferMb,
                usedHeapMb = usedHeapMb,
                maxHeapMb = maxHeapMb,
                estimatedDrainRate = "~1.0% – 1.5% / h",
            )

            return SettingsUiState(
                retentionStepper = stepper,
                qualityPresets = presetOptions,
                selectedPreset = pendingPreset,
                clampNotice = clampNotice,
                telemetry = telemetry,
                resizeError = resizeError,
            )
        }

        /** Standard [ViewModelProvider.Factory] wiring, used from [cc.machado.audioblackbox.ui.MainActivity]'s
         * `viewModel(factory = ...)` call -- same shape as [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel.Factory]
         * and for the same reason: this class's constructor parameters all have defaults for
         * testability, which defeats reflection-based construction. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel() }
        }
    }
}
