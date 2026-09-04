package cc.machado.audioblackbox.ui.dashboard

/**
 * The two rules that decide what the REMOVE BEFORE FLIGHT tag may do on the dashboard (issue #284):
 * whether it is on the panel at all, and whether pulling it may arm the recorder.
 *
 * They were inline expressions in [DashboardScreen] until PR #320's review (`@rev` finding 4)
 * pointed out the consequence: the single most safety-critical rule in that PR -- **the gesture can
 * only ever arm a capture, never stop one** -- had no coverage at any test tier and was defended
 * only by people reading it. Both are pure functions of [CaptureStatus] and [EngineSwitchUiState],
 * so there is no reason for that. Lifted out here they are exhaustively JVM-testable over every
 * [CaptureStatus], which `RemoveBeforeFlightTagPolicyTest` does against the real
 * [DashboardViewModel.mapEngineSwitchState] rather than against hand-built switch states.
 *
 * Note what is *not* here: everything that happens once a drag is under way -- the threshold, the
 * once-only latch, the enabled gate -- lives in
 * [cc.machado.audioblackbox.ui.theme.RemoveBeforeFlightDragGate]. This object answers the question
 * one level up, before any finger touches the tag: may this state offer the route at all.
 */
object RemoveBeforeFlightTagPolicy {

    /**
     * Whether the tag is rendered at all. It is the standby banner, so it is on the panel while the
     * recorder is idle, and while the switch is off for any other reason (an [CaptureStatus.Error],
     * or the frames between a stop being dispatched and the capture actually ending).
     */
    fun isOnPanel(status: CaptureStatus, engineSwitch: EngineSwitchUiState): Boolean =
        status is CaptureStatus.Idle || !engineSwitch.checked

    /**
     * Whether pulling the tag may call `onToggleEngine()`.
     *
     * Keyed on `checked` and nothing else, because `checked` is exactly "the recorder is running"
     * (see [EngineSwitchUiState]'s doc: it is never set optimistically, only ever derived from a
     * real [CaptureStatus]). So a `false` here is the one thing standing between an accidental drag
     * and a stopped capture, and it is what makes the gesture one-way: while the recorder runs,
     * `onToggleEngine` is not wired to the tag at all, and neither the drag nor the named
     * accessibility action has anything to call.
     *
     * This is deliberately stricter than [isOnPanel]. In the transient state where the switch is
     * already on but the capture has not reached [CaptureStatus.Recording] yet, the tag is still
     * shown -- and there a pull would *stop* the recorder, which is the one thing this app exists
     * not to do by accident. It stays the passive banner it always was instead.
     */
    fun mayArm(engineSwitch: EngineSwitchUiState): Boolean = !engineSwitch.checked
}
