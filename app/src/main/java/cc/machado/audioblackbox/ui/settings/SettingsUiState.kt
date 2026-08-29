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
 * *actually* running at right now. They differ ([isDirty]) whenever the user has moved the stepper
 * or changed quality preset but not yet tapped Apply -- deliberately: adjusting settings must never itself discard buffered
 * audio (see [SettingsViewModel]'s class doc for why that gap exists and why closing it needs an
 * explicit action rather than applying on every tap).
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
    /** Non-null only while [SettingsViewModel.commitPendingRetentionWindow] is waiting on the
     * user's explicit discard confirmation -- the same enforcement point issue #45's dialog
     * existed for, now fired exactly once per commit rather than once per stepper tap. */
    val pendingConfirmationMinutes: Int?,
    val maxSelectableMinutes: Int = AudioConfig.RETENTION_WINDOW_MAX_MINUTES,
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
)
