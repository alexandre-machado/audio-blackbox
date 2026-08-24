package cc.machado.audioblackbox.ui.settings

/**
 * One frame of the retention stepper (issue #73, superseding the dashboard's fixed-chip selector
 * from #45/#57). [pendingMinutes] is what the stepper currently displays and what the -/+ controls
 * adjust; [committedMinutes] is what [cc.machado.audioblackbox.service.RecorderService]'s engine is
 * *actually* running at right now. They differ ([isDirty]) whenever the user has moved the stepper
 * but not yet tapped Apply -- deliberately: adjusting the stepper must never itself discard buffered
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
    /** Same as [canDecrement], for
     * [AudioConfig.RETENTION_WINDOW_MAX_MINUTES][cc.machado.audioblackbox.audio.AudioConfig.RETENTION_WINDOW_MAX_MINUTES]
     * and the `+` control. */
    val canIncrement: Boolean,
    /** `true` while [pendingMinutes] differs from [committedMinutes] -- the pending-vs-active
     * distinction issue #73 requires stay visible on screen, not just internally tracked. */
    val isDirty: Boolean,
    /** Non-null only while [SettingsViewModel.commitPendingRetentionWindow] is waiting on the
     * user's explicit discard confirmation -- the same enforcement point issue #45's dialog
     * existed for, now fired exactly once per commit rather than once per stepper tap. */
    val pendingConfirmationMinutes: Int?,
)

/** Everything [SettingsScreen] needs to render one frame. A single field today ([retentionStepper])
 * because the retention control is this screen's only content as of issue #73; kept as a wrapping
 * data class (rather than [SettingsScreen] taking [RetentionStepperUiState] directly) so a future
 * settings addition does not need every call site's signature to change. */
data class SettingsUiState(
    val retentionStepper: RetentionStepperUiState,
)
