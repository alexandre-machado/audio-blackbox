package cc.machado.audioblackbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Maps [RecorderService]'s observable engine state onto [DashboardUiState] and dispatches the
 * dashboard's user actions back onto the service. Deliberately does not keep any parallel copy
 * of "is it recording" truth: [captureState] defaults to
 * [RecorderService.Companion.engine]'s own `StateFlow`, which is a singleton for the process
 * lifetime (see that class's doc), so a freshly constructed ViewModel -- after rotation, or after
 * the Activity is recreated while the service keeps running in the background -- immediately
 * reflects whatever the service's real state already is, rather than starting from a guess.
 *
 * ## The window-selector gap (flagged on issue #6)
 * [RecorderService.saveIntent] takes no window-length parameter; `handleSave()` always exports
 * everything the capture engine currently has buffered, up to [capacityMinutes]. Trimming that
 * down to a shorter window in this ViewModel was considered and rejected -- the issue explicitly
 * forbids duplicating the export engine's windowing logic in the UI layer, and doing so here
 * would silently diverge from whatever [cc.machado.audioblackbox.export.ExportEngine] actually
 * writes (gap handling, WAV header, filename). Until the service exposes a window-minutes extra
 * threaded through to `exportEngine.export(...)`, [computeWindowOptions] can only ever enable the
 * option matching [capacityMinutes] itself, and only once the buffer actually holds that much --
 * see that function's doc for the exact rule.
 *
 * ## The save-progress gap (flagged on issue #6)
 * [RecorderService] does not expose `exportEngine.state` outside the service instance (it is a
 * `private val`, constructed per-Service-instance because it needs a `Context`) -- only the
 * persistent notification currently renders Exporting/Success/Error. So this ViewModel cannot
 * tell a real "still writing" from "finished" from "failed"; [requestSave] can only record that
 * the intent was sent ([SaveUiState.Requested]) and point the user at the notification for the
 * real outcome. Wiring true progress/success/error into this screen needs
 * [RecorderService] to publish that `StateFlow` the same way it already does for [captureState].
 */
class DashboardViewModel(
    private val captureState: StateFlow<CaptureState> = RecorderService.engine.state,
    private val bufferedDurationMillisProvider: () -> Long? = RecorderService.engine::bufferedDurationMillis,
    private val capacityMinutes: Int = AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val onStartEngine: () -> Unit = {},
    private val onStopEngine: () -> Unit = {},
    private val onSaveIntent: (minutes: Int) -> Unit = {},
) : ViewModel() {

    private val _saveState = MutableStateFlow<SaveUiState>(SaveUiState.Idle)

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

    val uiState: StateFlow<DashboardUiState> = combine(
        captureState,
        bufferedMillisFlow,
        _saveState,
    ) { capture, bufferedMillis, saveState ->
        mapUiState(capture, bufferedMillis, capacityMinutes, saveState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            captureState.value,
            bufferedDurationMillisProvider() ?: 0L,
            capacityMinutes,
            SaveUiState.Idle,
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
     * error case worth surfacing. */
    fun requestSave(minutes: Int) {
        val option = uiState.value.windowOptions.firstOrNull { it.minutes == minutes } ?: return
        if (!option.enabled) return
        onSaveIntent(minutes)
        _saveState.value = SaveUiState.Requested(minutes)
        viewModelScope.launch {
            delay(SAVE_NOTICE_VISIBLE_MILLIS)
            if (_saveState.value == SaveUiState.Requested(minutes)) {
                _saveState.value = SaveUiState.Idle
            }
        }
    }

    /** Lets the user dismiss the "pedido enviado" notice early instead of waiting out the
     * auto-hide timeout above. */
    fun dismissSaveNotice() {
        _saveState.value = SaveUiState.Idle
    }

    companion object {
        private const val DEFAULT_TICK_MILLIS = 500L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val SAVE_NOTICE_VISIBLE_MILLIS = 6_000L

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
         * Builds the "salvar o passado" window selector from what's actually buffered.
         *
         * The engine only ever supports exporting *everything currently buffered, up to
         * [capacityMinutes]* (see [RecorderService.handleSave] / [DashboardViewModel]'s class
         * doc) -- there is no way today to request a strictly shorter window without either
         * risking a silently-truncated file or duplicating the export engine's windowing logic in
         * this layer, both of which issue #6 forbids. So exactly one rule decides every option's
         * state:
         *   - an option is [WindowOption.enabled] only when its `minutes` equals
         *     [capacityMinutes] **and** the buffer already holds at least that much audio --
         *     the one case where "export everything buffered" and "export the requested window"
         *     coincide exactly;
         *   - otherwise, if the buffer holds less than that option's minutes, the reason is
         *     [WindowDisabledReason.INSUFFICIENT_BUFFER] (surfaced as "só X min disponíveis" --
         *     this also covers the [capacityMinutes] option itself while the buffer is still
         *     filling up);
         *   - otherwise (enough audio is buffered, but this option isn't the one that matches
         *     [capacityMinutes]) the reason is [WindowDisabledReason.PARTIAL_WINDOW_NOT_SUPPORTED].
         */
        fun computeWindowOptions(
            bufferedMillis: Long,
            capacityMinutes: Int,
            optionsMinutes: List<Int> = DashboardUiState.WINDOW_OPTION_MINUTES,
        ): List<WindowOption> {
            val availableMinutes = (bufferedMillis / MILLIS_PER_MINUTE).toInt()
            return optionsMinutes.map { minutes ->
                when {
                    minutes > availableMinutes -> WindowOption(
                        minutes = minutes,
                        availableMinutes = availableMinutes,
                        enabled = false,
                        disabledReason = WindowDisabledReason.INSUFFICIENT_BUFFER,
                    )
                    minutes != capacityMinutes -> WindowOption(
                        minutes = minutes,
                        availableMinutes = availableMinutes,
                        enabled = false,
                        disabledReason = WindowDisabledReason.PARTIAL_WINDOW_NOT_SUPPORTED,
                    )
                    else -> WindowOption(
                        minutes = minutes,
                        availableMinutes = availableMinutes,
                        enabled = true,
                        disabledReason = null,
                    )
                }
            }
        }

        /** The single state-mapping oracle issue #6 requires be unit-tested: engine state +
         * buffered duration (+ the in-flight save notice) -> the exact [DashboardUiState] the
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
                windowOptions = computeWindowOptions(clampedBufferedMillis, capacityMinutes),
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
