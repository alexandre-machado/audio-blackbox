package cc.machado.audioblackbox.ui.settings

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
 * to rule out, just triggered by navigation instead of a second tap. See [onCleared]'s own doc for
 * the one case this still cannot fully close: a flush that is refused after the screen is gone.
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
    private val _resizeError = MutableStateFlow<ResizeErrorInfo?>(null)

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
            resizeError = null,
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
     * ## The one case this does not fully close: a refusal after the screen is already gone
     * If the flushed commit is refused, [commitPending] still sets [_resizeError] and calls
     * [RecorderService.acknowledgeResizeRefusal] exactly as it would in-session -- but by
     * construction there is no longer any [SettingsScreen] mounted to read either, so the user gets
     * no notification of the refusal this time. This is a deliberate, stated limitation, not a
     * silent gap: the property issue #272 actually protects -- the persisted preference and the
     * live engine's real capacity can never diverge -- still holds, because [commitPending] only
     * ever writes the preference on success, in this path exactly as in every other. What is lost is
     * purely the *notification*, in the narrow double-fault of "navigated away mid-debounce" AND
     * "this exact device is currently too memory-constrained to grant the request" landing in the
     * same commit. A real fix (e.g. surfacing the still-unacknowledged refusal the next time
     * Settings is reopened) is not implemented here because it is not verifiable at this repo's Tier
     * 0: [RecorderService.switchSettings] only ever refuses through the real ring buffer's real
     * `MemoryBudget` check, which requires a live, `AudioRecord`-backed buffer -- unreachable from a
     * JVM test (see `AGENTS.md`'s Tier 0 blind spots) -- so a fix here could not be given real
     * regression coverage, only the appearance of it. */
    // `public`, widened from the base `protected` (Kotlin permits widening on override): this is
    // the seam `SettingsViewModelTest` calls directly to simulate `ViewModel.clear()` -- `clear()`
    // itself is `internal` to `androidx.lifecycle`, unreachable from this module's own tests, and
    // `onCleared()` is what `clear()` actually invokes, so calling it directly is the real thing,
    // not a proxy for it.
    public override fun onCleared() {
        commitJob?.cancel()
        val minutes = currentPendingMinutes()
        val preset = currentPendingPreset()
        if (minutes != capacityMinutesFlow.value || preset != qualityPresetFlow.value) {
            commitFlushScope.launch { commitPending() }
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
    private suspend fun commitPending() {
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
            _resizeError.value = describeRefusal(refusal, minutes)
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

        /** `@rev` finding on PR #304: a companion-owned scope, deliberately *not* parented to any
         * one [SettingsViewModel] instance's [viewModelScope] -- it must survive exactly the event
         * ([onCleared]) that cancels that scope. Mirrors the existing pattern one level down in this
         * same codebase, [RecorderService]'s own companion-owned `forwardingScope`/`serviceScope`
         * (process-lifetime, outliving any one Service/ViewModel instance for the same structural
         * reason). Does not leak: every use here ([onCleared]) launches at most one short-lived,
         * already-bounded coroutine (a single [commitPending] call, no loop, no retry) per cleared
         * instance that had a genuinely dirty pending edit -- nothing keeps this scope's own
         * [SupervisorJob] artificially alive, and a completed child coroutine is not retained. */
        private val commitFlushScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
