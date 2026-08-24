package cc.machado.audioblackbox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderService
import cc.machado.audioblackbox.settings.InMemoryRetentionWindowPreferences
import cc.machado.audioblackbox.settings.RetentionWindowPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the retention-window stepper (issue #73), which moved here from
 * [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s fixed-chip selector (#45, hardened
 * on #57). The stepper's core requirement is the reason this class exists as its own ViewModel with
 * its own local, uncommitted state rather than just widening the old chip logic:
 *
 * ## Why a pending value, not "apply on every tap" (the whole point of this class)
 * Changing the retention window rebuilds [RecorderService]'s engine, which discards whatever audio
 * is currently buffered (see [RecorderService.rebuildEngineIfIdle]'s doc). The old chip selector
 * showed a discard-confirmation dialog per tap, which was fine for four discrete choices tapped
 * rarely. A `-`/`+` stepper is tapped repeatedly and rapidly by design -- showing that same dialog on
 * every single tap would be unusable, and applying every tap silently would violate the "never
 * discard buffered audio without explicit confirmation" rule #45 exists for.
 *
 * The fix: [incrementPending]/[decrementPending] only ever move [_pendingMinutes], a purely local
 * value nothing downstream observes yet. Nothing is persisted, rebuilt, or discarded until
 * [commitPendingRetentionWindow] -- an explicit, separate action -- is called, and even then the
 * discard-confirmation dialog ([_pendingConfirmationMinutes], rendered by
 * [SettingsScreen]'s `RetentionDiscardDialog]) fires at most once per commit, only when
 * [captureState] is not already [CaptureState.Idle] (an Idle engine holds no buffered audio -- see
 * [RecorderService.rebuildEngineIfIdle]'s doc -- so there is nothing to lose and no reason to make
 * the user confirm a no-op-risk action).
 *
 * Navigating away from this screen (switching the floating bottom bar's tab, issue #73) never
 * commits anything either: [_pendingMinutes] simply stops mattering until the screen is shown
 * again, since nothing reads it except this ViewModel's own [uiState]. An implicit commit on
 * navigation would be a worse failure mode than an extra tap -- exactly the shape of mistake this
 * whole class exists to avoid.
 */
class SettingsViewModel(
    // Same forwarding rationale as DashboardViewModel's own captureState/capacityMinutesFlow
    // parameters (see that class's doc): a retention-window rebuild replaces the underlying engine
    // instance wholesale, so this must read RecorderService's *forwarded* StateFlow, never a
    // one-time snapshot that would freeze at whatever it saw at construction.
    private val captureState: StateFlow<CaptureState> = RecorderService.captureState,
    private val capacityMinutesFlow: StateFlow<Int> = RecorderService.bufferDurationMinutesFlow,
    private val onStopEngine: () -> Unit = {},
    // Persists the user's retention-window choice. Defaults to an in-memory fake (this
    // constructor deliberately takes no Context, mirroring DashboardViewModel's own shape);
    // MainActivity injects the real DataStore-backed instance.
    private val retentionWindowPreferences: RetentionWindowPreferences = InMemoryRetentionWindowPreferences(),
    // Rebuilds RecorderService's process-lifetime engine at the new capacity. Returns `false` (and
    // this ViewModel does nothing further) if the engine was not Idle when called -- see
    // RecorderService.rebuildEngineIfIdle's doc. confirmRetentionWindowChange() below already
    // stops the engine and waits for Idle first, so that `false` path is only ever reachable by a
    // genuine race, not the normal flow.
    private val onRebuildEngine: (minutes: Int) -> Boolean = RecorderService::rebuildEngineIfIdle,
) : ViewModel() {

    // The stepper's local, uncommitted value -- see class doc's "Why a pending value" section.
    // Seeded from whatever capacity is actually committed right now, not
    // AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES: a freshly opened settings screen must show the
    // truth, not always restart at the default.
    private val _pendingMinutes = MutableStateFlow(capacityMinutesFlow.value)

    // Non-null while commitPendingRetentionWindow() is waiting on the user's explicit discard
    // confirmation -- set there when the engine is not Idle, cleared by
    // confirmRetentionWindowChange()/cancelRetentionWindowChange(). See those methods' docs for
    // the full flow; this is only the piece of state [uiState] renders.
    private val _pendingConfirmationMinutes = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        capacityMinutesFlow,
        _pendingMinutes,
        _pendingConfirmationMinutes,
    ) { committed, pending, pendingConfirmation ->
        mapUiState(committed, pending, pendingConfirmation)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            capacityMinutesFlow.value,
            _pendingMinutes.value,
            _pendingConfirmationMinutes.value,
        ),
    )

    /** Moves the stepper's pending value up by [AudioConfig.RETENTION_WINDOW_STEP_MINUTES],
     * clamped at [AudioConfig.RETENTION_WINDOW_MAX_MINUTES]. Never persists or rebuilds anything --
     * see class doc. Ignored while a discard confirmation is pending: the stepper is locked until
     * the user resolves that dialog, so an in-flight commit's target minutes can never be moved out
     * from under it by a stray tap that lands while it is showing. */
    fun incrementPending() {
        if (_pendingConfirmationMinutes.value != null) return
        _pendingMinutes.value = (_pendingMinutes.value + AudioConfig.RETENTION_WINDOW_STEP_MINUTES)
            .coerceAtMost(AudioConfig.RETENTION_WINDOW_MAX_MINUTES)
    }

    /** Symmetric with [incrementPending], clamped at [AudioConfig.RETENTION_WINDOW_MIN_MINUTES]. */
    fun decrementPending() {
        if (_pendingConfirmationMinutes.value != null) return
        _pendingMinutes.value = (_pendingMinutes.value - AudioConfig.RETENTION_WINDOW_STEP_MINUTES)
            .coerceAtLeast(AudioConfig.RETENTION_WINDOW_MIN_MINUTES)
    }

    /**
     * The stepper's explicit "apply" action (issue #73) -- the only path that can ever persist or
     * rebuild. A no-op if the pending value already matches what is committed (nothing to apply),
     * or if a confirmation is already pending (a stray double-tap on Apply while the dialog is
     * already showing must not re-evaluate/replace it).
     *
     * If capture is currently [CaptureState.Idle], the change applies immediately -- same
     * reasoning as the old chip selector's Idle-fast-path (see [RecorderService.rebuildEngineIfIdle]'s
     * doc: `stop()` already clears the ring buffer before reaching Idle, so there is nothing to
     * lose). Otherwise this only records the request in [uiState] as
     * [RetentionStepperUiState.pendingConfirmationMinutes] -- [SettingsScreen] renders that as the
     * discard-warning dialog, fired exactly once for this commit, and nothing is persisted or
     * rebuilt until [confirmRetentionWindowChange] is called.
     */
    fun commitPendingRetentionWindow() {
        val minutes = _pendingMinutes.value
        if (minutes == capacityMinutesFlow.value) return
        if (_pendingConfirmationMinutes.value != null) return
        if (captureState.value is CaptureState.Idle) {
            applyRetentionWindow(minutes)
        } else {
            _pendingConfirmationMinutes.value = minutes
        }
    }

    /** Confirms the pending retention-window change from [commitPendingRetentionWindow], accepting
     * the loss of whatever is currently buffered. No-op if nothing is pending (a stale double-tap
     * on a dialog that already closed).
     *
     * Stops the engine first ([onStopEngine]) and suspends until [captureState] actually reaches
     * [CaptureState.Idle] before applying the new window -- this is the same discard [onStopEngine]
     * would already cause on its own, so confirming this dialog costs the user nothing beyond what
     * stopping the recording directly already would have. */
    fun confirmRetentionWindowChange() {
        val minutes = _pendingConfirmationMinutes.value ?: return
        _pendingConfirmationMinutes.value = null
        onStopEngine()
        viewModelScope.launch {
            captureState.first { it is CaptureState.Idle }
            applyRetentionWindow(minutes)
        }
    }

    /** Lets the user back out of the discard-warning dialog without changing anything -- the
     * engine keeps running exactly as it was, and the stepper keeps showing whatever pending value
     * the user had chosen (it is not reverted -- only the confirmation itself is dismissed). */
    fun cancelRetentionWindowChange() {
        _pendingConfirmationMinutes.value = null
    }

    /** Persists [minutes] then rebuilds RecorderService's engine at that capacity. Only ever called
     * while the engine is actually Idle (see the two call sites above), so [onRebuildEngine]
     * succeeding is the expected case; a `false` return (the engine somehow transitioned back to
     * Recording/Paused in the narrow window between the Idle check and this call) is logged nowhere
     * further here because there is nothing actionable to surface: the persisted preference and the
     * running engine's actual capacity would disagree until the user tries again, a narrow,
     * self-correcting race, not a silent data-loss path. */
    private fun applyRetentionWindow(minutes: Int) {
        viewModelScope.launch {
            retentionWindowPreferences.setBufferDurationMinutes(minutes)
            onRebuildEngine(minutes)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val BYTES_PER_MB = 1_000_000L

        /** The single state-mapping oracle for this screen: committed capacity + local pending
         * value + pending-confirmation flag -> the exact [SettingsUiState] the screen renders. */
        fun mapUiState(
            committedMinutes: Int,
            pendingMinutes: Int,
            pendingConfirmationMinutes: Int?,
        ): SettingsUiState {
            val stepper = RetentionStepperUiState(
                pendingMinutes = pendingMinutes,
                committedMinutes = committedMinutes,
                approxPendingRamMb = (
                    AudioConfig(bufferDurationMinutes = pendingMinutes).totalBufferBytes / BYTES_PER_MB
                    ).toInt(),
                canDecrement = pendingMinutes > AudioConfig.RETENTION_WINDOW_MIN_MINUTES,
                canIncrement = pendingMinutes < AudioConfig.RETENTION_WINDOW_MAX_MINUTES,
                isDirty = pendingMinutes != committedMinutes,
                pendingConfirmationMinutes = pendingConfirmationMinutes,
            )
            return SettingsUiState(retentionStepper = stepper)
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
