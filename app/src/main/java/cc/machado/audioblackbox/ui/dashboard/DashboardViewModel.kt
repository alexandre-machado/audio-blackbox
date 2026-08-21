package cc.machado.audioblackbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.service.RecorderService
import cc.machado.audioblackbox.settings.InMemoryRetentionWindowPreferences
import cc.machado.audioblackbox.settings.RetentionWindowPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    // Issue #45: reads the companion's *forwarded* captureState/bufferDurationMinutesFlow, not
    // `RecorderService.engine.state`/a one-time snapshot `Int` -- a retention-window change
    // replaces the underlying `engine` instance wholesale, and a reference captured once here
    // (the way both of these used to be) would freeze at whatever it saw at ViewModel
    // construction and never observe another transition/capacity change afterwards. See
    // RecorderService.captureState's doc for why the forwarding exists.
    private val captureState: StateFlow<CaptureState> = RecorderService.captureState,
    private val bufferedDurationMillisProvider: () -> Long? = { RecorderService.engine.bufferedDurationMillis() },
    // Issue #40 item 3 (`@rev` finding on issue #6), extended by issue #45: reads
    // RecorderService's real, *reactive* configured capacity instead of a bare constant or a
    // one-time snapshot, so both the buffer denominator and the retention selector's "selected"
    // option stay correct across a rebuild without needing this ViewModel recreated.
    private val capacityMinutesFlow: StateFlow<Int> = RecorderService.bufferDurationMinutesFlow,
    private val exportState: StateFlow<ExportState> = RecorderService.exportState,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val onStartEngine: () -> Unit = {},
    private val onStopEngine: () -> Unit = {},
    private val onSaveIntent: (minutes: Int) -> Unit = {},
    // Issue #45: persists the user's retention-window choice. Defaults to an in-memory fake (this
    // constructor deliberately takes no Context -- see the class doc above); MainActivity injects
    // the real DataStore-backed instance.
    private val retentionWindowPreferences: RetentionWindowPreferences = InMemoryRetentionWindowPreferences(),
    // Issue #45: rebuilds RecorderService's process-lifetime engine at the new capacity. Returns
    // `false` (and this ViewModel does nothing further) if the engine was not Idle when called --
    // see RecorderService.rebuildEngineIfIdle's doc. Callers of this ViewModel's own
    // confirmRetentionWindowChange() already stop the engine and wait for Idle first, so that
    // `false` path is only ever reachable by a genuine race, not the normal flow.
    private val onRebuildEngine: (minutes: Int) -> Boolean = RecorderService::rebuildEngineIfIdle,
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

    // Non-null while a retention-window change is waiting on the user's explicit discard
    // confirmation (issue #45) -- set by selectRetentionWindow() when the engine is not Idle,
    // cleared by confirmRetentionWindowChange()/cancelRetentionWindowChange(). See those methods'
    // docs for the full flow; this is only the piece of state [uiState] renders.
    private val _pendingRetentionConfirmationMinutes = MutableStateFlow<Int?>(null)

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

    // Bundles capacityMinutesFlow + the pending-confirmation flag into one Flow<Pair<..>> because
    // kotlinx.coroutines.flow.combine only has a direct overload up to 5 flows, and this ViewModel
    // already needs 4 others below (captureState, bufferedMillisFlow, exportState,
    // _dismissedExportState) -- nesting here keeps the outer combine within that limit rather than
    // reaching for the vararg overload, which requires every flow to share one element type.
    private val retentionInputsFlow: Flow<Pair<Int, Int?>> =
        combine(capacityMinutesFlow, _pendingRetentionConfirmationMinutes) { capacity, pending -> capacity to pending }

    val uiState: StateFlow<DashboardUiState> = combine(
        captureState,
        bufferedMillisFlow,
        exportState,
        _dismissedExportState,
        retentionInputsFlow,
    ) { capture, bufferedMillis, export, dismissed, (capacityMinutes, pendingRetentionMinutes) ->
        mapUiState(
            capture,
            bufferedMillis,
            capacityMinutes,
            mapSaveUiState(export, dismissed),
            pendingRetentionMinutes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            captureState.value,
            bufferedDurationMillisProvider() ?: 0L,
            capacityMinutesFlow.value,
            mapSaveUiState(exportState.value, _dismissedExportState.value),
            _pendingRetentionConfirmationMinutes.value,
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

    /**
     * User tapped a retention-window option (issue #45). A no-op if [minutes] already matches the
     * current capacity, or if a confirmation is already pending -- a second tap while the dialog
     * is showing must not silently swap which change is pending.
     *
     * If capture is currently [CaptureState.Idle], the change applies immediately: an Idle engine
     * holds no buffered audio (see [RecorderService.rebuildEngineIfIdle]'s doc -- `stop()` already
     * clears the ring buffer before reaching Idle), so there is nothing to lose and no reason to
     * make the user confirm a no-op-risk action.
     *
     * Otherwise (Recording/Paused/Error with audio still buffered) this only records the request
     * in [uiState] as [RetentionSectionUiState.pendingConfirmationMinutes] -- [DashboardScreen]
     * renders that as the discard-warning dialog, and nothing is persisted or rebuilt until
     * [confirmRetentionWindowChange] is called. This is the enforcement point the whole feature
     * exists for: never discard the user's buffered audio because they opened a settings screen.
     */
    fun selectRetentionWindow(minutes: Int) {
        if (minutes == capacityMinutesFlow.value) return
        if (_pendingRetentionConfirmationMinutes.value != null) return
        if (captureState.value is CaptureState.Idle) {
            applyRetentionWindow(minutes)
        } else {
            _pendingRetentionConfirmationMinutes.value = minutes
        }
    }

    /** Confirms the pending retention-window change from [selectRetentionWindow], accepting the
     * loss of whatever is currently buffered. No-op if nothing is pending (a stale double-tap on a
     * dialog that already closed).
     *
     * Stops the engine first ([onStopEngine]) and suspends until [captureState] actually reaches
     * [CaptureState.Idle] before applying the new window -- this is the same discard [onStopEngine]
     * would already cause on its own (see [RecorderService.rebuildEngineIfIdle]'s doc: `stop()`
     * clears the ring buffer before Idle), so confirming this dialog costs the user nothing beyond
     * what tapping "Parar motor" already would have. */
    fun confirmRetentionWindowChange() {
        val minutes = _pendingRetentionConfirmationMinutes.value ?: return
        _pendingRetentionConfirmationMinutes.value = null
        onStopEngine()
        viewModelScope.launch {
            captureState.first { it is CaptureState.Idle }
            applyRetentionWindow(minutes)
        }
    }

    /** Lets the user back out of the discard-warning dialog without changing anything -- the
     * engine keeps running exactly as it was. */
    fun cancelRetentionWindowChange() {
        _pendingRetentionConfirmationMinutes.value = null
    }

    /** Persists [minutes] then rebuilds RecorderService's engine at that capacity. Only ever
     * called while the engine is actually Idle (see the two call sites above), so
     * [onRebuildEngine] succeeding is the expected case; a `false` return (the engine somehow
     * transitioned back to Recording/Paused in the narrow window between the Idle check and this
     * call -- e.g. an OS-redelivered start Intent) is logged nowhere further here because there is
     * nothing actionable to surface: the persisted preference and the running engine's actual
     * capacity would disagree until the user tries again, which is a narrow, self-correcting race,
     * not a silent data-loss path. */
    private fun applyRetentionWindow(minutes: Int) {
        viewModelScope.launch {
            retentionWindowPreferences.setBufferDurationMinutes(minutes)
            onRebuildEngine(minutes)
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

        /**
         * Builds the retention-window selector (issue #45) from the currently configured
         * capacity: [RetentionWindowOption.selected] marks the one entry matching
         * [currentCapacityMinutes] -- [RecorderService.bufferDurationMinutes]/`Flow`, never a
         * second, independently-tracked "which one is chosen" value, so this can never disagree
         * with what the engine is actually running at. [RetentionWindowOption.approxRamMb] is the
         * exact arithmetic [cc.machado.audioblackbox.audio.AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES]'s
         * doc comment justifies the bounds against -- computed via `AudioConfig.totalBufferBytes`
         * rather than a second hardcoded table, so it can never drift from the real sizing formula.
         */
        fun computeRetentionSection(
            currentCapacityMinutes: Int,
            pendingConfirmationMinutes: Int?,
            optionsMinutes: List<Int> = AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES,
        ): RetentionSectionUiState {
            val options = optionsMinutes.map { minutes ->
                RetentionWindowOption(
                    minutes = minutes,
                    approxRamMb = (AudioConfig(bufferDurationMinutes = minutes).totalBufferBytes / BYTES_PER_MB).toInt(),
                    selected = minutes == currentCapacityMinutes,
                )
            }
            return RetentionSectionUiState(options = options, pendingConfirmationMinutes = pendingConfirmationMinutes)
        }

        /** The single state-mapping oracle issue #6 requires be unit-tested: engine state +
         * buffered duration (+ the in-flight save outcome, + issue #45's retention selector) ->
         * the exact [DashboardUiState] the screen renders. */
        fun mapUiState(
            captureState: CaptureState,
            bufferedMillis: Long,
            capacityMinutes: Int,
            saveState: SaveUiState,
            pendingRetentionConfirmationMinutes: Int? = null,
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
                retentionSection = computeRetentionSection(capacityMinutes, pendingRetentionConfirmationMinutes),
            )
        }

        private const val MILLIS_PER_MINUTE = 60_000L
        private const val BYTES_PER_MB = 1_000_000L

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
