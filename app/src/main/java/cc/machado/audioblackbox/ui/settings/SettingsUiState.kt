package cc.machado.audioblackbox.ui.settings

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.settings.ClampNotice

/**
 * One item in the quality preset selector (issue #193).
 */
data class QualityPresetOption(
    val preset: QualityPreset,
    val maxRetentionMinutes: Int,
    val isSelected: Boolean,
)

/**
 * One frame of the retention stepper (issue #73, superseding the dashboard's fixed-chip selector
 * from #45/#57). [pendingMinutes] is what the stepper currently displays and what the -/+ controls
 * adjust; [committedMinutes] is what [cc.machado.audioblackbox.service.RecorderService]'s engine is
 * *actually* running at right now. They differ ([isDirty]) for the brief window between a tap and
 * [SettingsViewModel]'s debounced auto-commit (issue #299) catching up -- there is no Apply step any
 * more: in-place buffer resizing (issue #223) and quality-preset switching (issue #194) both
 * preserve buffered audio across that boundary without stopping capture, so nothing needs guarding
 * by requiring an explicit action first (see [SettingsViewModel]'s class doc).
 */
data class RetentionStepperUiState(
    val pendingMinutes: Int,
    val committedMinutes: Int,
    /** [AudioConfig.totalBufferBytes][cc.machado.audioblackbox.audio.AudioConfig.totalBufferBytes]
     * at [pendingMinutes], in megabytes -- the "spending your device's memory" number the issue
     * requires stay visible, computed for the *pending* value so the user sees what they are about
     * to commit to, not just what is currently active. */
    val approxPendingRamMb: Int,
    /** `false` once [pendingMinutes] has reached
     * [AudioConfig.RETENTION_WINDOW_MIN_MINUTES][cc.machado.audioblackbox.audio.AudioConfig.RETENTION_WINDOW_MIN_MINUTES] --
     * exposed here so the screen can disable the `-` control with real accessible state, not just
     * grey it out. */
    val canDecrement: Boolean,
    /** Same as [canDecrement], for [maxSelectableMinutes] and the `+` control. */
    val canIncrement: Boolean,
    /** `true` while pending configuration differs from committed -- the pending-vs-active
     * distinction issue #73 requires stay visible on screen, not just internally tracked. */
    val isDirty: Boolean,
    // No AudioConfig constant to default to any more (issue #298) -- the real ceiling is
    // per-device and per-preset (see DeviceMemoryBudget.maxRetentionMinutes). This default only
    // matters for a caller that builds this state without going through
    // SettingsViewModel.mapUiState (i.e. a hand-written test/preview value); every real caller
    // passes its own computed value explicitly.
    val maxSelectableMinutes: Int = Int.MAX_VALUE,
)

/**
 * Real-time power, memory, and hardware telemetry metrics rendered on the settings screen.
 */
data class PowerTelemetryUiState(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = true,
    val bufferMemoryMb: Double = 0.0,
    val usedHeapMb: Double = 0.0,
    val maxHeapMb: Double = 0.0,
    val estimatedDrainRate: String = "~1.0% – 1.5% / h",
)

/**
 * Issue #272: the real, specific numbers behind a refused live buffer resize -- carried as data
 * rather than a pre-formatted [String] so [SettingsScreen] can render the message through
 * `strings.xml` (and get a real pt-BR translation) instead of a hardcoded Kotlin literal.
 * [requestedMb] is `null` only when the refusal's outcome details were unavailable (see
 * [SettingsViewModel.describeRefusal]); the message still names [requestedMinutes] either way.
 */
data class ResizeErrorInfo(
    val requestedMinutes: Int,
    val requestedMb: Int?,
)

/** Everything [SettingsScreen] needs to render one frame. */
data class SettingsUiState(
    val retentionStepper: RetentionStepperUiState,
    val qualityPresets: List<QualityPresetOption> = emptyList(),
    val selectedPreset: QualityPreset = QualityPreset.DEFAULT,
    /** Issue #84: non-null exactly while there is an unacknowledged notice that this device's
     * stored retention window was clamped down (e.g. from 60 to 45) by issue #72's interim safety
     * clamp. [SettingsScreen] renders this as a one-time dialog; dismissing it calls
     * [SettingsViewModel.acknowledgeClampNotice], after which this stays `null` for good. */
    val clampNotice: ClampNotice? = null,
    val telemetry: PowerTelemetryUiState = PowerTelemetryUiState(),
    /** Issue #272: non-null exactly while there is an unacknowledged "your settings change could
     * not be applied" refusal -- surfaced when a live buffer resize was refused because it could
     * not fit given the device's current heap state. [SettingsScreen] renders this as a real,
     * specific error (not a generic failure toast); dismissing it calls
     * [SettingsViewModel.dismissResizeError]. The previous, still-active setting stays in force --
     * this is a refusal, never a crash and never a silent no-op. */
    val resizeError: ResizeErrorInfo? = null,
)
