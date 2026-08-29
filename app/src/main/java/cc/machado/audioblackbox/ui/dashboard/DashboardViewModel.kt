package cc.machado.audioblackbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ForwardRecordingState
import cc.machado.audioblackbox.service.RecorderService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Frozen at the instant [DashboardViewModel] observes an [ExportState.Error] -- see
 * [SaveUiState.Error]'s doc for why this must not be re-derived from live state on every
 * recomposition. */
data class SaveErrorSnapshot(
    val timestampMillis: Long,
    val bufferedMillis: Long,
    val capacityMillis: Long,
    val qualityPreset: QualityPreset,
)

/** Frozen at the instant [DashboardViewModel] observes a [ForwardRecordingState.Error] -- see
 * [ForwardRecordingUiState.Error]'s doc for why this must not be re-derived from live state on
 * every recomposition. */
data class ForwardErrorSnapshot(
    val timestampMillis: Long,
    val capacityMillis: Long,
    val qualityPreset: QualityPreset,
)

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
    private val captureState: StateFlow<CaptureState> = RecorderService.captureState,
    private val bufferedDurationMillisProvider: () -> Long? = { RecorderService.engine.bufferedDurationMillis() },
    private val capacityMinutesFlow: StateFlow<Int> = RecorderService.bufferDurationMinutesFlow,
    private val exportState: StateFlow<ExportState> = RecorderService.exportState,
    private val forwardRecordingState: StateFlow<ForwardRecordingState> = RecorderService.forwardRecordingState,
    private val inputLevelFlow: StateFlow<Float> = RecorderService.inputLevel,
    private val qualityPresetFlow: StateFlow<QualityPreset> = RecorderService.qualityPresetFlow,
    private val audioConfigProvider: () -> AudioConfig = { RecorderService.captureConfig },
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    // Injectable so a test can pin the "moment of failure" instead of racing System.currentTimeMillis()
    // (issue #206, `@rev` finding on PR #207 -- see SaveErrorSnapshot/ForwardErrorSnapshot's doc).
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val onStartEngine: () -> Unit = {},
    private val onStopEngine: () -> Unit = {},
    private val onSaveIntent: () -> Unit = {},
    private val onStartForwardRecording: () -> Unit = {},
    private val onStopForwardRecording: () -> Unit = {},
) : ViewModel() {

    // The most recent terminal ExportState (Success/Error) the user has explicitly dismissed --
    // see dismissSaveNotice()/mapSaveUiState(). null means "nothing dismissed yet", which is also
    // what a freshly constructed ViewModel starts with even if exportState already holds a
    // terminal value from before this ViewModel existed (e.g. after rotation, right after a save
    // just finished) -- that outcome is still worth showing once, not swallowed silently.
    private val _dismissedExportState = MutableStateFlow<ExportState?>(null)
    private val _dismissedForwardRecordingState = MutableStateFlow<ForwardRecordingState?>(null)

    // The frozen "at the moment of failure" snapshots backing SaveUiState.Error/ForwardRecordingUiState.Error
    // (issue #206, `@rev` finding on PR #207): captured once, below, the instant exportState/
    // forwardRecordingState is observed to become Error -- never re-derived from live state, which is
    // what let the old "remember(uiState) { ... }" in DashboardScreen drift to "now" every ~500ms tick
    // while capture kept running after the failure. Seeded eagerly here (not left null until the
    // init collectors below run) so uiState's own `initialValue` -- computed further down, still in
    // this constructor -- is correct even for a ViewModel constructed while an Error already exists
    // (e.g. after rotation), without depending on Dispatchers.Main.immediate replaying the collector
    // synchronously before that point.
    private val _saveErrorSnapshot = MutableStateFlow(
        if (exportState.value is ExportState.Error) freshSaveErrorSnapshot() else null,
    )
    private val _forwardErrorSnapshot = MutableStateFlow(
        if (forwardRecordingState.value is ForwardRecordingState.Error) freshForwardErrorSnapshot() else null,
    )

    private fun freshSaveErrorSnapshot(): SaveErrorSnapshot = SaveErrorSnapshot(
        timestampMillis = nowMillisProvider(),
        bufferedMillis = bufferedDurationMillisProvider() ?: 0L,
        capacityMillis = capacityMinutesFlow.value.toLong() * MILLIS_PER_MINUTE,
        qualityPreset = qualityPresetFlow.value,
    )

    private fun freshForwardErrorSnapshot(): ForwardErrorSnapshot = ForwardErrorSnapshot(
        timestampMillis = nowMillisProvider(),
        capacityMillis = capacityMinutesFlow.value.toLong() * MILLIS_PER_MINUTE,
        qualityPreset = qualityPresetFlow.value,
    )

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

    // The currently-live backstop Job scheduled by requestSave() below, or null when no dispatch
    // is pending (issue #50, `@rev` advisory on PR #43). Cancelled and cleared by every path that
    // releases saveDispatchPending -- the init collector below, and requestSave()'s own `finally`
    // -- so at most one backstop timer is ever armed at a time. Without this, an earlier dispatch's
    // timer (e.g. one released early by a throw) stays scheduled and can later fire during a
    // *subsequent* dispatch's own in-flight window, clearing that later call's guard before its
    // own confirmation or its own backstop has actually arrived.
    private var dispatchTimeoutJob: Job? = null

    // Mirrors saveDispatchPending for startForwardRecording() (issue #208): protects against rapid
    // double-taps before RecorderService has transitioned forwardRecordingState, avoiding duplicate
    // start dispatches and race conditions.
    private var forwardDispatchPending = false

    // Mirrors dispatchTimeoutJob for startForwardRecording() (issue #208).
    private var forwardDispatchTimeoutJob: Job? = null

    // True from the moment toggleEngine() dispatches a start/stop request until captureState is
    // observed to actually change (issue #46) -- the *only* local state this class keeps for the
    // engine switch, and it never substitutes for the real CaptureState: it only ever gates
    // [EngineSwitchUiState.enabled]/[EngineSwitchUiState.pending], never [EngineSwitchUiState.checked]
    // (see that class's doc for why). Starts `false` unconditionally -- including right after
    // process death, when this ViewModel is reconstructed fresh -- so there is no stale "pending"
    // value to reconcile: a freshly built instance has never dispatched anything, so it is simply
    // never pending until toggleEngine() is called again on the new instance. Reconciled with the
    // real state by the collector in init below, plus a backstop timeout (see toggleEngine()'s doc)
    // for the case the dispatch itself never reaches the service.
    private val _engineTogglePending = MutableStateFlow(false)

    // Mirrors dispatchTimeoutJob (see its doc) for the engine-switch toggle instead of the save
    // dispatch -- same shape, same reason: at most one backstop timer armed at a time, cancelled by
    // every path that clears _engineTogglePending so an earlier backstop can never fire during a
    // later toggle's own in-flight window.
    private var engineToggleTimeoutJob: Job? = null

    init {
        // Clears saveDispatchPending the moment a real, non-Idle ExportState is observed --
        // deliberately not "once it returns to Idle", which would leave the flag stuck at `true`
        // forever after the very first save (Idle -> Exporting -> Success/Error -> Idle would
        // never re-clear it, since it was already cleared going into Exporting and nothing ever
        // sets it back to true except a fresh requestSave() call).
        viewModelScope.launch {
            exportState.collect { state ->
                // Freeze a fresh snapshot every time a (possibly new) Error is observed -- issue
                // #206, `@rev` finding on PR #207. StateFlow only re-emits on a genuinely distinct
                // value, so an unrelated recomposition of uiState (e.g. bufferedMillisFlow's tick)
                // never re-runs this and never re-freezes the snapshot; a second, different Error
                // correctly gets its own fresh one.
                if (state is ExportState.Error) {
                    _saveErrorSnapshot.value = freshSaveErrorSnapshot()
                }
                if (state !is ExportState.Idle) {
                    saveDispatchPending = false
                    dispatchTimeoutJob?.cancel()
                    dispatchTimeoutJob = null
                }
            }
        }

        // Mirrors the exportState collector above for forward recording's own Error and dispatch release (issues #206, #208).
        viewModelScope.launch {
            forwardRecordingState.collect { state ->
                if (state is ForwardRecordingState.Error) {
                    _forwardErrorSnapshot.value = freshForwardErrorSnapshot()
                }
                if (state !is ForwardRecordingState.Idle) {
                    forwardDispatchPending = false
                    forwardDispatchTimeoutJob?.cancel()
                    forwardDispatchTimeoutJob = null
                }
            }
        }

        // Clears _engineTogglePending the moment captureState is observed to change to anything
        // different from what it was the moment collection started (issue #46) -- that first real
        // transition IS the toggle's outcome, whatever it is (Recording, Paused, Error, or back to
        // Idle), so there is nothing further to wait for. `previous` starts `null` specifically so
        // the very first (replayed) emission from this StateFlow never itself counts as "changed" --
        // otherwise a ViewModel constructed while already pending (there is no such call path today,
        // but nothing here should rely on that) would clear itself on its own initial read instead of
        // on a genuine transition. Deliberately does not try to distinguish *which* transition it
        // was: MutableStateFlow conflates by design (see AGENTS note on this class), so an
        // intermediate state between two rapid emissions can be skipped entirely, and that is fine --
        // this only ever needs to know that the real state moved at all.
        viewModelScope.launch {
            var previous: CaptureState? = null
            captureState.collect { current ->
                if (previous != null && current != previous && _engineTogglePending.value) {
                    _engineTogglePending.value = false
                    engineToggleTimeoutJob?.cancel()
                    engineToggleTimeoutJob = null
                }
                previous = current
            }
        }
    }

    // Bundles capacityMinutesFlow + the engine-switch pending flag (issue #46) into one
    // Flow<Pair<..>> because kotlinx.coroutines.flow.combine only has a direct overload up to 5
    // flows, and this ViewModel already needs 4 others below (captureState, bufferedMillisFlow,
    // exportState, _dismissedExportState) -- nesting here keeps the outer combine within that
    // limit rather than reaching for the vararg overload, which requires every flow to share one
    // element type. (Issue #73 removed a third member of this bundle -- the retention discard
    // confirmation flag -- when the retention control moved to SettingsViewModel; kept as a Pair
    // rather than un-nested back to a plain 6-arg combine for the same reason it existed before.)
    private val exportAndDismissedFlow: Flow<Pair<ExportState, ExportState?>> =
        combine(exportState, _dismissedExportState) { exp, dism -> exp to dism }

    private val forwardAndDismissedFlow: Flow<Pair<ForwardRecordingState, ForwardRecordingState?>> =
        combine(forwardRecordingState, _dismissedForwardRecordingState) { fwd, dism -> fwd to dism }

    private data class ExtraDashboardState(
        val capacityMinutes: Int,
        val enginePending: Boolean,
        val inputLevel: Float,
        val qualityPreset: QualityPreset,
        val saveErrorSnapshot: SaveErrorSnapshot?,
        val forwardErrorSnapshot: ForwardErrorSnapshot?,
    )

    // Bundles the two error snapshots into one Flow<Pair<..>> for the same reason
    // exportAndDismissedFlow/forwardAndDismissedFlow are nested above -- combine's direct overloads
    // only go up to 5 flows, and extraStateFlow below already needs 4 others.
    private val errorSnapshotsFlow: Flow<Pair<SaveErrorSnapshot?, ForwardErrorSnapshot?>> =
        combine(_saveErrorSnapshot, _forwardErrorSnapshot) { save, forward -> save to forward }

    private val extraStateFlow: Flow<ExtraDashboardState> =
        combine(
            capacityMinutesFlow,
            _engineTogglePending,
            inputLevelFlow,
            qualityPresetFlow,
            errorSnapshotsFlow,
        ) { capacity, enginePending, level, preset, (saveSnapshot, forwardSnapshot) ->
            ExtraDashboardState(capacity, enginePending, level, preset, saveSnapshot, forwardSnapshot)
        }

    val uiState: StateFlow<DashboardUiState> = combine(
        captureState,
        bufferedMillisFlow,
        exportAndDismissedFlow,
        forwardAndDismissedFlow,
        extraStateFlow,
    ) { capture, bufferedMillis, (export, dismissedExport), (forward, dismissedForward), extra ->
        mapUiState(
            captureState = capture,
            bufferedMillis = bufferedMillis,
            capacityMinutes = extra.capacityMinutes,
            saveState = mapSaveUiState(export, dismissedExport, extra.saveErrorSnapshot),
            forwardRecordingState = mapForwardRecordingUiState(
                forwardState = forward,
                dismissed = dismissedForward,
                bytesPerSecond = audioConfigProvider().bytesPerSecond,
                snapshot = extra.forwardErrorSnapshot,
            ),
            enginePending = extra.enginePending,
            inputLevel = extra.inputLevel,
            qualityPreset = extra.qualityPreset,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            captureState = captureState.value,
            bufferedMillis = bufferedDurationMillisProvider() ?: 0L,
            capacityMinutes = capacityMinutesFlow.value,
            saveState = mapSaveUiState(exportState.value, _dismissedExportState.value, _saveErrorSnapshot.value),
            forwardRecordingState = mapForwardRecordingUiState(
                forwardState = forwardRecordingState.value,
                dismissed = _dismissedForwardRecordingState.value,
                bytesPerSecond = audioConfigProvider().bytesPerSecond,
                snapshot = _forwardErrorSnapshot.value,
            ),
            enginePending = _engineTogglePending.value,
            inputLevel = inputLevelFlow.value,
            qualityPreset = qualityPresetFlow.value,
        ),
    )

    /** Single engine switch (issue #46, formerly a Start/Stop button): starts the engine unless it
     * is already Recording or Paused (in either of which "stop" is the correct next action),
     * matching [RecorderService]'s own idempotent start()/stop() semantics.
     *
     * Ignored outright while [_engineTogglePending] is already `true` -- the dashboard's Switch is
     * disabled for the same duration (see [mapEngineSwitchState]), so this only guards against a
     * stale composition reaching this call anyway, the same defensive shape as [requestSave]'s own
     * guard.
     *
     * [_engineTogglePending] is released three ways, none of which can leave it stuck (mirroring
     * [requestSave]'s own three-way release, for the same reason -- see its doc):
     *   - the `init` collector above, the instant [captureState] is observed to actually change;
     *   - immediately, via the `finally` below, if the dispatch itself throws;
     *   - after [ENGINE_TOGGLE_TIMEOUT_MILLIS], via the scheduled backstop, covering a dispatch
     *     that returns normally but never reaches [RecorderService] (a dropped Intent, the service
     *     failing to start).
     */
    fun toggleEngine() {
        if (_engineTogglePending.value) return
        _engineTogglePending.value = true

        engineToggleTimeoutJob = viewModelScope.launch {
            delay(ENGINE_TOGGLE_TIMEOUT_MILLIS)
            _engineTogglePending.value = false
            engineToggleTimeoutJob = null
        }

        var dispatchThrew = true
        try {
            when (captureState.value) {
                is CaptureState.Recording, is CaptureState.Paused -> onStopEngine()
                is CaptureState.Idle, is CaptureState.Error -> onStartEngine()
            }
            dispatchThrew = false
        } finally {
            if (dispatchThrew) {
                _engineTogglePending.value = false
                engineToggleTimeoutJob?.cancel()
                engineToggleTimeoutJob = null
            }
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
     * The backstop's [Job] is captured in [dispatchTimeoutJob] and explicitly cancelled by every
     * other release path (the `finally` below, and the `init` collector once a real [ExportState]
     * arrives) -- issue #50, a `@rev` advisory on PR #43: an uncancelled backstop from an earlier
     * call stays scheduled and can fire during a *later* call's own in-flight window, releasing
     * that later call's guard before its own confirmation or its own backstop has arrived. See
     * [dispatchTimeoutJob]'s own doc.
     *
     * Clears any previously dismissed outcome so this new export's own Success/Error is
     * guaranteed to be shown once it lands, even in the (practically impossible, since filenames
     * are timestamped) case that it would otherwise compare equal to whatever was last
     * dismissed. */
    /** Fires the save request to save the entire buffered audio window if there is audio in memory.
     * Ignored if the buffer is empty or if a save is already in flight.
     *
     * Clears any previously dismissed outcome so this new export's own Success/Error is
     * guaranteed to be shown once it lands. */
    fun requestSave() {
        if (uiState.value.bufferedMillis <= 0L) return
        if (saveDispatchPending || exportState.value !is ExportState.Idle) return
        saveDispatchPending = true
        _dismissedExportState.value = null

        dispatchTimeoutJob = viewModelScope.launch {
            delay(DISPATCH_TIMEOUT_MILLIS)
            saveDispatchPending = false
            dispatchTimeoutJob = null
        }

        var dispatchThrew = true
        try {
            onSaveIntent()
            dispatchThrew = false
        } finally {
            if (dispatchThrew) {
                saveDispatchPending = false
                dispatchTimeoutJob?.cancel()
                dispatchTimeoutJob = null
            }
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
    /** Starts a forward recording session. Always includes the retained past (issue #139) --
     * [ForwardRecordingEngine.start] itself has no forward-only mode anymore, so there is nothing
     * left for this call to parameterize.
     *
     * Protected by [forwardDispatchPending] against rapid double-taps while a dispatch is in flight (issue #208).
     */
    fun startForwardRecording() {
        if (forwardDispatchPending || forwardRecordingState.value is ForwardRecordingState.Recording) return
        forwardDispatchPending = true
        _dismissedForwardRecordingState.value = null

        forwardDispatchTimeoutJob = viewModelScope.launch {
            delay(DISPATCH_TIMEOUT_MILLIS)
            forwardDispatchPending = false
            forwardDispatchTimeoutJob = null
        }

        var dispatchThrew = true
        try {
            onStartForwardRecording()
            dispatchThrew = false
        } finally {
            if (dispatchThrew) {
                forwardDispatchPending = false
                forwardDispatchTimeoutJob?.cancel()
                forwardDispatchTimeoutJob = null
            }
        }
    }

    fun stopForwardRecording() {
        if (forwardRecordingState.value !is ForwardRecordingState.Recording) return
        onStopForwardRecording()
    }

    fun dismissForwardRecordingNotice() {
        val current = forwardRecordingState.value
        if (current is ForwardRecordingState.Success || current is ForwardRecordingState.Error) {
            _dismissedForwardRecordingState.value = current
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

        // Backstop for toggleEngine()'s _engineTogglePending guard (issue #46, same reasoning as
        // DISPATCH_TIMEOUT_MILLIS above): AudioCaptureEngine.start()/stop() themselves run
        // synchronously once RecorderService actually receives the Intent (see that class's doc),
        // so this only needs to cover the Intent/Binder hop plus foreground-service startup, not
        // the capture logic itself -- same order of magnitude as the save dispatch, hence the same
        // value.
        private const val ENGINE_TOGGLE_TIMEOUT_MILLIS = 5_000L

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

        /**
         * The oracle issue #46 requires: every [CaptureStatus] maps to exactly one
         * [EngineSwitchUiState], parameterized only by whether a toggle dispatched by
         * [DashboardViewModel.toggleEngine] is still awaiting its real outcome. See
         * [EngineSwitchUiState]'s own doc for why Paused stays `checked` and why a pending toggle
         * disables the switch instead of moving it.
         */
        fun mapEngineSwitchState(status: CaptureStatus, pending: Boolean): EngineSwitchUiState = when (status) {
            is CaptureStatus.Idle -> EngineSwitchUiState(
                checked = false, enabled = !pending, pending = pending, paused = false, error = null,
            )
            is CaptureStatus.Recording -> EngineSwitchUiState(
                checked = true, enabled = !pending, pending = pending, paused = false, error = null,
            )
            is CaptureStatus.Paused -> EngineSwitchUiState(
                checked = true, enabled = !pending, pending = pending, paused = true, error = null,
            )
            is CaptureStatus.Error -> EngineSwitchUiState(
                checked = false, enabled = !pending, pending = pending, paused = false, error = status,
            )
        }

        /** 1:1 mapping from [ExportState] to [SaveUiState] (issue #40 item 2), except that a
         * terminal [ExportState.Success]/[ExportState.Error] that equals [dismissed] (see
         * [dismissSaveNotice]) maps to [SaveUiState.Idle] instead -- once the user has acknowledged
         * an outcome, it must not keep reappearing every time [uiState] recomposes from an
         * unrelated emission (the buffered-duration tick, in particular, fires every [tickMillis]
         * regardless of export state). */
        /** [snapshot] is the frozen "at the moment of failure" state -- see [SaveErrorSnapshot]'s
         * doc -- and backs [SaveUiState.Error]'s own frozen fields (issue #206, `@rev` finding on
         * PR #207). Defaults to `null` only for callers that don't care about those fields (e.g. a
         * pure-mapping test asserting solely on [reason]/[message]); production always supplies a
         * real snapshot captured the instant [DashboardViewModel] observed this [ExportState.Error]. */
        fun mapSaveUiState(
            exportState: ExportState,
            dismissed: ExportState?,
            snapshot: SaveErrorSnapshot? = null,
        ): SaveUiState = when (exportState) {
            is ExportState.Idle -> SaveUiState.Idle
            is ExportState.Exporting -> SaveUiState.Exporting
            is ExportState.Success ->
                if (exportState == dismissed) SaveUiState.Idle else SaveUiState.Success(exportState.displayName)
            is ExportState.Error ->
                if (exportState == dismissed) {
                    SaveUiState.Idle
                } else {
                    val snap = snapshot ?: SaveErrorSnapshot(0L, 0L, 0L, QualityPreset.DEFAULT)
                    SaveUiState.Error(
                        reason = exportState.reason,
                        message = exportState.message,
                        timestampMillis = snap.timestampMillis,
                        bufferedMillis = snap.bufferedMillis,
                        capacityMillis = snap.capacityMillis,
                        qualityPreset = snap.qualityPreset,
                    )
                }
        }

        /** See [mapSaveUiState]'s doc for [snapshot]'s role -- the same frozen-at-failure snapshot,
         * here for [ForwardRecordingUiState.Error] (issue #206). */
        fun mapForwardRecordingUiState(
            forwardState: ForwardRecordingState,
            dismissed: ForwardRecordingState?,
            bytesPerSecond: Int,
            snapshot: ForwardErrorSnapshot? = null,
        ): ForwardRecordingUiState = when (forwardState) {
            is ForwardRecordingState.Idle -> ForwardRecordingUiState.Idle
            is ForwardRecordingState.Recording -> {
                val elapsedMillis = if (bytesPerSecond > 0) {
                    (forwardState.bytesWritten * 1000L) / bytesPerSecond
                } else {
                    0L
                }
                ForwardRecordingUiState.Recording(
                    displayName = forwardState.displayName,
                    elapsedMillis = elapsedMillis,
                )
            }
            is ForwardRecordingState.Success ->
                if (forwardState == dismissed) ForwardRecordingUiState.Idle
                else ForwardRecordingUiState.Success(forwardState.displayName, forwardState.bytesWritten)
            is ForwardRecordingState.Error ->
                if (forwardState == dismissed) {
                    ForwardRecordingUiState.Idle
                } else {
                    val snap = snapshot ?: ForwardErrorSnapshot(0L, 0L, QualityPreset.DEFAULT)
                    ForwardRecordingUiState.Error(
                        reason = forwardState.reason,
                        message = forwardState.message,
                        timestampMillis = snap.timestampMillis,
                        capacityMillis = snap.capacityMillis,
                        qualityPreset = snap.qualityPreset,
                    )
                }
        }

        /** The single state-mapping oracle issue #6 requires be unit-tested: engine state +
         * buffered duration + the in-flight save outcome -> the exact [DashboardUiState] the
         * screen renders. Issue #73 moved the retention selector (formerly folded in here per
         * issue #45) to [cc.machado.audioblackbox.ui.settings.SettingsViewModel]'s own oracle.
         * Issue #121 retired the window options selector in favor of a single save action. */
        fun mapUiState(
            captureState: CaptureState,
            bufferedMillis: Long,
            capacityMinutes: Int,
            saveState: SaveUiState,
            forwardRecordingState: ForwardRecordingUiState = ForwardRecordingUiState.Idle,
            enginePending: Boolean = false,
            inputLevel: Float = 0f,
            qualityPreset: QualityPreset = QualityPreset.DEFAULT,
        ): DashboardUiState {
            val capacityMillis = capacityMinutes.toLong() * MILLIS_PER_MINUTE
            val clampedBufferedMillis = bufferedMillis.coerceIn(0L, capacityMillis)
            val captureStatus = mapCaptureStatus(captureState)
            return DashboardUiState(
                captureStatus = captureStatus,
                engineSwitch = mapEngineSwitchState(captureStatus, enginePending),
                bufferedMillis = clampedBufferedMillis,
                capacityMillis = capacityMillis,
                isBufferFull = clampedBufferedMillis >= capacityMillis,
                saveState = saveState,
                forwardRecordingState = forwardRecordingState,
                qualityPreset = qualityPreset,
                // Zeroed unless actually Recording, and clamped rather than trusted. Paused is the
                // case that matters: audio is not reaching the ring buffer then, so a meter showing
                // anything above empty would be claiming capture that is not happening -- the
                // failure the old animated placeholder embodied (issue #155 would have been
                // invisible behind it).
                inputLevel = if (captureStatus is CaptureStatus.Recording) {
                    inputLevel.coerceIn(0f, 1f)
                } else {
                    0f
                },
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
