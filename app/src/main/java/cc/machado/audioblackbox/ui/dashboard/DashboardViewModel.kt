package cc.machado.audioblackbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.service.RecorderService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Maps [RecorderService]'s observable engine/export state onto [DashboardUiState] and dispatches
 * the dashboard's user actions back onto the service. Deliberately does not keep any parallel
 * copy of "is it recording" truth: [captureState] defaults to
 * [RecorderService.Companion.engine]'s own `StateFlow`, which is a singleton for the process
 * lifetime (see that class's doc), so a freshly constructed ViewModel -- after rotation, or after
 * the Activity is recreated while the service keeps running in the background -- immediately
 * reflects whatever the service's real state already is, rather than starting from a guess.
 * [exportState] and [capacityMinutes] follow the same pattern -- see their own docs below.
 *
 * ## The window selector (issue #40 item 1, closing the gap flagged on issue #6)
 * [RecorderService.saveIntent] now threads a requested window in minutes through `ACTION_SAVE`
 * into `exportEngine.export(...)`, so [computeWindowOptions] can enable every option the buffer
 * actually holds enough audio for, not only the one matching [capacityMinutes]. This ViewModel
 * still does not trim or duplicate any windowing itself: [requestSave] only ever forwards the
 * exact minutes value an already-enabled [WindowOption] carries, and
 * [cc.machado.audioblackbox.export.ExportEngine]/[cc.machado.audioblackbox.audio.RingBuffer.snapshot]
 * are the only things that ever clamp a request down to what is truly buffered.
 *
 * ## Real save progress (issue #40 item 2, closing the gap flagged on issue #6)
 * [RecorderService] now publishes `exportEngine.state` from its companion object the same way it
 * already does for `engine.state` (as [exportState]), so [uiState] carries the export's real
 * [ExportState] -- Exporting/Success/Error -- not just "the intent was sent". [mapSaveUiState]
 * is the pure oracle for that mapping. Opening the saved file from the success confirmation still
 * needs the gallery (issue #7) and is not attempted here -- see [SaveUiState.Success]'s doc.
 */
class DashboardViewModel(
    private val captureState: StateFlow<CaptureState> = RecorderService.engine.state,
    private val bufferedDurationMillisProvider: () -> Long? = RecorderService.engine::bufferedDurationMillis,
    // Issue #40 item 3 (`@rev` finding on issue #6): reads RecorderService's real configured
    // capacity instead of the bare AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES constant this used
    // to default to -- both are 30 today, but only this keeps them from being able to drift, since
    // this is the same value captureConfig/engine/exportEngine are all actually built from.
    private val capacityMinutes: Int = RecorderService.bufferDurationMinutes,
    private val exportState: StateFlow<ExportState> = RecorderService.exportState,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val onStartEngine: () -> Unit = {},
    private val onStopEngine: () -> Unit = {},
    private val onSaveIntent: (minutes: Int) -> Unit = {},
) : ViewModel() {

    // The most recent terminal ExportState (Success/Error) the user has explicitly dismissed --
    // see dismissSaveNotice()/mapSaveUiState(). null means "nothing dismissed yet", which is also
    // what a freshly constructed ViewModel starts with even if exportState already holds a
    // terminal value from before this ViewModel existed (e.g. after rotation, right after a save
    // just finished) -- that outcome is still worth showing once, not swallowed silently.
    private val _dismissedExportState = MutableStateFlow<ExportState?>(null)

    // Polled rather than a StateFlow because AudioCaptureEngine.bufferedDurationMillis() is a
    // plain getter, not itself observable (see that method's doc) -- it changes continuously
    // while Recording without any discrete "transition" to react to, the same reason
    // PeriodicNotificationRefresher exists service-side (issue #30). Ticking only while this flow
    // has a subscriber (governed by the `stateIn` below) means it costs nothing while the screen
    // is not visible.
    private val bufferedMillisFlow: Flow<Long> = flow {
        while (true) {
            emit(bufferedDurationMillisProvider() ?: 0L)
            delay(tickMillis)
        }
    }

    // Guards a rapid double-tap of requestSave() against dispatching onSaveIntent twice for what
    // was a single user action (issue #40 follow-up -- `@techlead`, off the back of issue #41's
    // instance-behaviour probe / PR #42, and issue #29's manual "tap Salvar twice" checklist item).
    //
    // ExportEngine already refuses a truly concurrent export (EXPORT_ALREADY_IN_PROGRESS) -- no
    // two exports can ever run at once and no file is at risk regardless of this flag. What this
    // guards is narrower: without it, a second tap that lands before RecorderService's async
    // round trip has had any chance to flip `exportState` away from Idle sails past the
    // `exportState.value !is ExportState.Idle` check below too (both taps still observe Idle) and
    // reaches onSaveIntent a second time, which -- now that issue #40 item 2 wires the real
    // ExportState onto this screen -- surfaces ExportEngine's rejection as a user-visible error for
    // what was just a duplicated tap on a single action.
    //
    // Plain (non-Flow) field, not a second StateFlow: every read/write happens on this ViewModel's
    // own single-threaded dispatcher (Dispatchers.Main.immediate, same as viewModelScope) --
    // requestSave() is only ever called from Compose's UI thread, and the collector below that
    // clears it runs on viewModelScope, which is that same dispatcher. No synchronization needed
    // for a value only ever touched from one thread.
    //
    // Must never be able to get stuck at `true` -- a permanent Save lockout was already a blocking
    // finding once in this project (issue #30's stranded-notification class), and `@techlead`'s
    // adjudication on PR #43 raised the same risk here: if `onSaveIntent` throws, or the dispatch
    // otherwise never reaches RecorderService, nothing would ever flip `exportState` away from
    // Idle to release this flag via the collector below. See requestSave()'s own doc for the two
    // release paths that cover both failure shapes.
    private var saveDispatchPending = false

    init {
        // Clears saveDispatchPending the moment a real, non-Idle ExportState is observed --
        // deliberately not "once it returns to Idle", which would leave the flag stuck at `true`
        // forever after the very first save (Idle -> Exporting -> Success/Error -> Idle would
        // never re-clear it, since it was already cleared going into Exporting and nothing ever
        // sets it back to true except a fresh requestSave() call).
        viewModelScope.launch {
            exportState.collect { state ->
                if (state !is ExportState.Idle) saveDispatchPending = false
            }
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        captureState,
        bufferedMillisFlow,
        exportState,
        _dismissedExportState,
    ) { capture, bufferedMillis, export, dismissed ->
        mapUiState(capture, bufferedMillis, capacityMinutes, mapSaveUiState(export, dismissed))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            captureState.value,
            bufferedDurationMillisProvider() ?: 0L,
            capacityMinutes,
            mapSaveUiState(exportState.value, _dismissedExportState.value),
        ),
    )

    /** Single Start/Stop control: starts the engine unless it is already Recording or Paused (in
     * either of which "stop" is the correct next action), matching [RecorderService]'s own
     * idempotent start()/stop() semantics. */
    fun toggleEngine() {
        when (captureState.value) {
            is CaptureState.Recording, is CaptureState.Paused -> onStopEngine()
            is CaptureState.Idle, is CaptureState.Error -> onStartEngine()
        }
    }

    /** Fires the save request for [minutes] if -- and only if -- [uiState] currently reports that
     * option as enabled; a disabled option can only be reached by a stale composition, never by
     * an actual tap on an enabled control, so silently ignoring it here is correct rather than an
     * error case worth surfacing.
     *
     * Also ignored while a save is already in flight -- either a real [ExportState.Exporting]
     * ([exportState] read directly, not through [uiState], so this is accurate even if nothing is
     * currently collecting [uiState]), or the narrower pre-real-state gap [saveDispatchPending]
     * covers (see its doc). This is the double-tap guard: without it, two rapid taps before
     * [RecorderService] has dispatched anything both observe [ExportState.Idle] and both reach
     * [onSaveIntent], which [cc.machado.audioblackbox.export.ExportEngine] would then reject as
     * [cc.machado.audioblackbox.export.ExportFailureReason.EXPORT_ALREADY_IN_PROGRESS] -- a
     * user-visible error for what was just one action.
     *
     * [saveDispatchPending] is released two ways, neither of which can leave it stuck (`@techlead`
     * adjudication on PR #43, raised to blocking -- a permanent Save lockout was already a
     * blocking finding once in this project, issue #30's stranded-notification class):
     *   - immediately, via the `finally` below, if [onSaveIntent] itself throws -- a thrown
     *     dispatch never reached [RecorderService], so there is nothing for the real
     *     [ExportState] to ever transition on, and the exception is rethrown once the guard is
     *     released rather than swallowed;
     *   - after [DISPATCH_TIMEOUT_MILLIS], via the scheduled backstop below, covering the case
     *     [onSaveIntent] returns normally but the dispatch still never reaches the service (an
     *     Intent silently dropped, the service failing to start) -- no exception to catch there,
     *     so only a bounded timeout can recover it. Both releases are safe no-ops once a real,
     *     genuinely in-flight export is underway: [exportState]'s own `!is Idle` check in the
     *     guard above already covers that case on its own, independent of this flag.
     *
     * Clears any previously dismissed outcome so this new export's own Success/Error is
     * guaranteed to be shown once it lands, even in the (practically impossible, since filenames
     * are timestamped) case that it would otherwise compare equal to whatever was last
     * dismissed. */
    fun requestSave(minutes: Int) {
        val option = uiState.value.windowOptions.firstOrNull { it.minutes == minutes } ?: return
        if (!option.enabled) return
        if (saveDispatchPending || exportState.value !is ExportState.Idle) return
        saveDispatchPending = true
        _dismissedExportState.value = null

        viewModelScope.launch {
            delay(DISPATCH_TIMEOUT_MILLIS)
            saveDispatchPending = false
        }

        var dispatchThrew = true
        try {
            onSaveIntent(minutes)
            dispatchThrew = false
        } finally {
            if (dispatchThrew) saveDispatchPending = false
        }
    }

    /** Lets the user dismiss the on-screen Success/Error confirmation early rather than waiting
     * for [RecorderService]'s own notification-visibility timeout to reset [exportState] back to
     * [ExportState.Idle]. No-op while [exportState] is [ExportState.Idle]/[ExportState.Exporting]
     * -- there is nothing to dismiss yet. */
    fun dismissSaveNotice() {
        val current = exportState.value
        if (current is ExportState.Success || current is ExportState.Error) {
            _dismissedExportState.value = current
        }
    }

    companion object {
        private const val DEFAULT_TICK_MILLIS = 500L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        // Backstop for requestSave()'s saveDispatchPending guard (see its doc): comfortably
        // longer than the async round trip normally takes (a coroutine dispatch plus one
        // Intent/Binder hop to RecorderService.onStartCommand), short enough that a genuine
        // dispatch failure does not lock the user out of Save for long.
        private const val DISPATCH_TIMEOUT_MILLIS = 5_000L

        /** 1:1 mapping, kept as its own pure function (rather than inlined into [mapUiState]) so
         * it has a single, obvious oracle: every [CaptureState] subtype maps to exactly the
         * [CaptureStatus] subtype of the same name, carrying [CaptureState.Error]'s fields
         * unchanged. */
        fun mapCaptureStatus(state: CaptureState): CaptureStatus = when (state) {
            is CaptureState.Idle -> CaptureStatus.Idle
            is CaptureState.Recording -> CaptureStatus.Recording
            is CaptureState.Paused -> CaptureStatus.Paused
            is CaptureState.Error -> CaptureStatus.Error(state.reason, state.message)
        }

        /** 1:1 mapping from [ExportState] to [SaveUiState] (issue #40 item 2), except that a
         * terminal [ExportState.Success]/[ExportState.Error] that equals [dismissed] (see
         * [dismissSaveNotice]) maps to [SaveUiState.Idle] instead -- once the user has acknowledged
         * an outcome, it must not keep reappearing every time [uiState] recomposes from an
         * unrelated emission (the buffered-duration tick, in particular, fires every [tickMillis]
         * regardless of export state). */
        fun mapSaveUiState(exportState: ExportState, dismissed: ExportState?): SaveUiState = when (exportState) {
            is ExportState.Idle -> SaveUiState.Idle
            is ExportState.Exporting -> SaveUiState.Exporting
            is ExportState.Success ->
                if (exportState == dismissed) SaveUiState.Idle else SaveUiState.Success(exportState.displayName)
            is ExportState.Error ->
                if (exportState == dismissed) SaveUiState.Idle else SaveUiState.Error(exportState.reason, exportState.message)
        }

        /**
         * Builds the "salvar o passado" window selector from what's actually buffered (issue #40
         * item 1). [RecorderService.saveIntent] can now request any window up to what is buffered
         * -- [ExportEngine][cc.machado.audioblackbox.export.ExportEngine]/
         * [RingBuffer.snapshot][cc.machado.audioblackbox.audio.RingBuffer.snapshot] clamp down to
         * what is actually available if a request ever exceeded it -- so the only rule left is:
         *   - an option is [WindowOption.enabled] once the buffer holds at least that many
         *     minutes;
         *   - otherwise it is disabled with [WindowDisabledReason.INSUFFICIENT_BUFFER], carrying
         *     [WindowOption.availableMinutes] so the UI can say "só X min disponíveis" -- this is
         *     the guarantee `@rev` verified on issue #6 and that must not regress: an option is
         *     never enabled unless the buffer can back it in full, so a save can never silently
         *     come back shorter than what was requested.
         */
        fun computeWindowOptions(
            bufferedMillis: Long,
            optionsMinutes: List<Int> = DashboardUiState.WINDOW_OPTION_MINUTES,
        ): List<WindowOption> {
            val availableMinutes = (bufferedMillis / MILLIS_PER_MINUTE).toInt()
            return optionsMinutes.map { minutes ->
                if (minutes > availableMinutes) {
                    WindowOption(
                        minutes = minutes,
                        availableMinutes = availableMinutes,
                        enabled = false,
                        disabledReason = WindowDisabledReason.INSUFFICIENT_BUFFER,
                    )
                } else {
                    WindowOption(
                        minutes = minutes,
                        availableMinutes = availableMinutes,
                        enabled = true,
                        disabledReason = null,
                    )
                }
            }
        }

        /** The single state-mapping oracle issue #6 requires be unit-tested: engine state +
         * buffered duration (+ the in-flight save outcome) -> the exact [DashboardUiState] the
         * screen renders. */
        fun mapUiState(
            captureState: CaptureState,
            bufferedMillis: Long,
            capacityMinutes: Int,
            saveState: SaveUiState,
        ): DashboardUiState {
            val capacityMillis = capacityMinutes.toLong() * MILLIS_PER_MINUTE
            val clampedBufferedMillis = bufferedMillis.coerceIn(0L, capacityMillis)
            return DashboardUiState(
                captureStatus = mapCaptureStatus(captureState),
                bufferedMillis = clampedBufferedMillis,
                capacityMillis = capacityMillis,
                isBufferFull = clampedBufferedMillis >= capacityMillis,
                windowOptions = computeWindowOptions(clampedBufferedMillis),
                saveState = saveState,
            )
        }

        private const val MILLIS_PER_MINUTE = 60_000L

        /** Standard [ViewModelProvider.Factory] wiring, used from [cc.machado.audioblackbox.ui.MainActivity]'s
         * `viewModel(factory = ...)` call. Not the no-arg-constructor default factory: this
         * class's constructor parameters all have defaults for testability, which defeats
         * reflection-based construction, so an explicit factory is the correct tool rather than
         * `@JvmOverloads` machinery. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardViewModel() }
        }
    }
}
