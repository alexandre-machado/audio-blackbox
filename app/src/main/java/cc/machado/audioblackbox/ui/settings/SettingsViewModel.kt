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
import cc.machado.audioblackbox.service.RecorderService
import cc.machado.audioblackbox.settings.ClampNotice
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
 * Owns the settings screen state: retention-window stepper (issue #73) and quality preset selector (issue #193).
 *
 * ## Why a pending value, not "apply on every tap"
 * Changing the retention window or preset rebuilds [RecorderService]'s engine, which discards whatever audio
 * is currently buffered (see [RecorderService.rebuildEngineIfIdle]'s doc).
 *
 * The fix: [incrementPending]/[decrementPending]/[selectQualityPreset] only ever move local pending
 * state nothing downstream observes yet. Nothing is persisted, rebuilt, or discarded until
 * [commitPendingRetentionWindow] -- an explicit, separate action -- is called, and even then the
 * discard-confirmation dialog fires at most once per commit, only when [captureState] is not already [CaptureState.Idle].
 */
class SettingsViewModel(
    private val captureState: StateFlow<CaptureState> = RecorderService.captureState,
    private val capacityMinutesFlow: StateFlow<Int> = RecorderService.bufferDurationMinutesFlow,
    private val qualityPresetFlow: StateFlow<QualityPreset> = RecorderService.qualityPresetFlow,
    private val onStopEngine: () -> Unit = {},
    private val retentionWindowPreferences: RetentionWindowPreferences = InMemoryRetentionWindowPreferences(),
    private val onSwitchSettings: ((minutes: Int, preset: QualityPreset) -> Unit)? = null,
    private val onRebuildEngine: (minutes: Int, preset: QualityPreset) -> Boolean = { m, p -> RecorderService.rebuildEngineIfIdle(m, p) },
    private val onSwitchQualityPreset: (QualityPreset) -> Unit = { RecorderService.switchQualityPreset(it) },
    private val maxMemoryBytesProvider: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val usedMemoryBytesProvider: () -> Long = { Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() },
    private val batteryStatusProvider: () -> cc.machado.audioblackbox.telemetry.BatteryStatus = {
        cc.machado.audioblackbox.telemetry.BatteryStatus(percent = 100, isCharging = false, isIgnoringOptimizations = true)
    },
) : ViewModel() {

    private val _pendingMinutes = MutableStateFlow<Int?>(null)
    private val _pendingPreset = MutableStateFlow<QualityPreset?>(null)

    data class PendingCommit(val minutes: Int, val preset: QualityPreset)
    private val _pendingConfirmation = MutableStateFlow<PendingCommit?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(capacityMinutesFlow, qualityPresetFlow, ::Pair),
        combine(_pendingMinutes, _pendingPreset, ::Pair),
        combine(_pendingConfirmation, retentionWindowPreferences.clampNoticeFlow, ::Pair),
    ) { (committedMins, committedPreset), (pendingMins, pendingPreset), (pendingConfirmation, clampNotice) ->
        val effectivePendingMins = pendingMins ?: committedMins
        val effectivePendingPreset = pendingPreset ?: committedPreset
        mapUiState(
            committedMinutes = committedMins,
            pendingMinutes = effectivePendingMins,
            committedPreset = committedPreset,
            pendingPreset = effectivePendingPreset,
            pendingConfirmation = pendingConfirmation,
            clampNotice = clampNotice,
            maxMemoryBytes = maxMemoryBytesProvider(),
            usedMemoryBytes = usedMemoryBytesProvider(),
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
            pendingConfirmation = null,
            clampNotice = null,
            maxMemoryBytes = maxMemoryBytesProvider(),
            usedMemoryBytes = usedMemoryBytesProvider(),
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
        )
    }

    private fun currentPendingMinutes(): Int = _pendingMinutes.value ?: capacityMinutesFlow.value
    private fun currentPendingPreset(): QualityPreset = _pendingPreset.value ?: qualityPresetFlow.value

    fun selectQualityPreset(preset: QualityPreset) {
        if (_pendingConfirmation.value != null) return
        _pendingPreset.value = preset
        val maxForPreset = maxRetentionForPreset(preset)
        val mins = currentPendingMinutes()
        if (mins > maxForPreset) {
            _pendingMinutes.value = maxForPreset
        }
    }

    fun incrementPending() {
        if (_pendingConfirmation.value != null) return
        val currentPreset = currentPendingPreset()
        val maxForPreset = maxRetentionForPreset(currentPreset)
        _pendingMinutes.value = (currentPendingMinutes() + AudioConfig.RETENTION_WINDOW_STEP_MINUTES)
            .coerceAtMost(maxForPreset)
    }

    fun decrementPending() {
        if (_pendingConfirmation.value != null) return
        _pendingMinutes.value = (currentPendingMinutes() - AudioConfig.RETENTION_WINDOW_STEP_MINUTES)
            .coerceAtLeast(AudioConfig.RETENTION_WINDOW_MIN_MINUTES)
    }

    fun commitPendingRetentionWindow() {
        val minutes = currentPendingMinutes()
        val preset = currentPendingPreset()
        val isDirty = minutes != capacityMinutesFlow.value || preset != qualityPresetFlow.value
        if (!isDirty) return
        if (_pendingConfirmation.value != null) return

        // In-place buffer resizing (issue #223) and quality preset switch (issue #194)
        // seamlessly preserve buffered audio across the boundary without stopping capture or discarding audio.
        applyChanges(minutes, preset)
    }

    fun confirmRetentionWindowChange() {
        val change = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        applyChanges(change.minutes, change.preset)
    }

    fun cancelRetentionWindowChange() {
        _pendingConfirmation.value = null
        resetPending()
    }

    fun resetPending() {
        _pendingMinutes.value = null
        _pendingPreset.value = null
    }

    private fun applyChanges(minutes: Int, preset: QualityPreset) {
        _pendingMinutes.value = null
        _pendingPreset.value = null
        viewModelScope.launch {
            retentionWindowPreferences.setBufferDurationMinutes(minutes)
            retentionWindowPreferences.setQualityPreset(preset)
            if (onSwitchSettings != null) {
                onSwitchSettings.invoke(minutes, preset)
            } else {
                RecorderService.switchSettings(minutes, preset)
                onRebuildEngine(minutes, preset)
                onSwitchQualityPreset(preset)
            }
        }
    }

    fun acknowledgeClampNotice() {
        viewModelScope.launch {
            retentionWindowPreferences.acknowledgeClampNotice()
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
            committedPreset: QualityPreset = QualityPreset.DEFAULT,
            pendingPreset: QualityPreset = committedPreset,
            pendingConfirmation: PendingCommit? = null,
            clampNotice: ClampNotice? = null,
            maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
            usedMemoryBytes: Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            batteryStatus: cc.machado.audioblackbox.telemetry.BatteryStatus = cc.machado.audioblackbox.telemetry.BatteryStatus(),
        ): SettingsUiState {
            val currentBufferBytes = committedPreset.config(bufferDurationMinutes = committedMinutes).totalBufferBytes
            val nonBufferUsedBytes = (usedMemoryBytes - currentBufferBytes).coerceAtLeast(0L)

            val presetOptions = QualityPreset.entries.map { preset ->
                val maxRetention = DeviceMemoryBudget.maxRetentionMinutes(
                    config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
                    maxHeapBytes = maxMemoryBytes,
                    usedHeapBytes = nonBufferUsedBytes,
                )
                QualityPresetOption(
                    preset = preset,
                    maxRetentionMinutes = maxRetention,
                    isSelected = preset == pendingPreset,
                )
            }
            val currentPresetMax = presetOptions.firstOrNull { it.preset == pendingPreset }?.maxRetentionMinutes
                ?: AudioConfig.RETENTION_WINDOW_MAX_MINUTES
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
                pendingConfirmationMinutes = pendingConfirmation?.minutes,
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
