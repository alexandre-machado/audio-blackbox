package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the REMOVE BEFORE FLIGHT drag rests on, under test at last: **pulling the tag can only
 * ever arm a capture, never stop one** (issue #284; PR #320 review, `@rev` finding 4, which pointed
 * out this was the most safety-critical rule in that PR and the only one with no coverage at any
 * tier).
 *
 * Oracle: every assertion here fails if [RemoveBeforeFlightTagPolicy] starts offering the arming
 * route, or the tag itself, in a state where the recorder is already capturing. The switch states
 * come from the real [DashboardViewModel.mapEngineSwitchState] rather than being hand-built, so
 * these are assertions about states the app can actually reach, not about a fixture written to
 * agree with the predicate (AGENTS.md §2, trap 1). The single hand-built state below is labelled as
 * such and tests the *defensive* branch precisely because the mapper cannot produce it.
 *
 * Exhaustive over [CaptureStatus]: `ALL_STATUSES` holds one value of each of the four variants the
 * sealed interface declares. Honestly, that exhaustiveness is maintained by hand, not by the
 * compiler -- a fifth variant would silently go untested here until someone added it, the same
 * as in `DashboardViewModelEngineSwitchTest`.
 */
class RemoveBeforeFlightTagPolicyTest {

    private companion object {
        val ERROR = CaptureStatus.Error(
            CaptureErrorReason.AUDIO_RECORD_INIT_FAILED,
            "AudioRecord.state = 0",
        )

        val ALL_STATUSES: List<CaptureStatus> = listOf(
            CaptureStatus.Idle,
            CaptureStatus.Recording,
            CaptureStatus.Paused,
            ERROR,
        )

        /** The states in which the engine holds the microphone and a stop would lose audio. */
        fun isCapturing(status: CaptureStatus): Boolean =
            status is CaptureStatus.Recording || status is CaptureStatus.Paused
    }

    private fun switchFor(status: CaptureStatus, pending: Boolean) =
        DashboardViewModel.mapEngineSwitchState(status, pending)

    @Test
    fun `no state in which a capture is running lets the tag arm anything`() {
        for (status in ALL_STATUSES) {
            for (pending in listOf(false, true)) {
                val switch = switchFor(status, pending)
                if (RemoveBeforeFlightTagPolicy.mayArm(switch)) {
                    assertFalse(
                        "the tag offers onToggleEngine in $status (pending=$pending), where " +
                            "toggling STOPS the capture -- the gesture must never be able to " +
                            "interrupt a recording, which is the one thing this app exists not " +
                            "to do by accident",
                        isCapturing(status),
                    )
                }
            }
        }
    }

    @Test
    fun `the tag is not even on the panel while a capture is running`() {
        for (status in ALL_STATUSES.filter(::isCapturing)) {
            for (pending in listOf(false, true)) {
                assertFalse(
                    "the standby tag must be off the panel in $status (pending=$pending): it is " +
                        "the banner for 'not recording', and a tag that is not composed cannot " +
                        "be dragged at all",
                    RemoveBeforeFlightTagPolicy.isOnPanel(status, switchFor(status, pending)),
                )
            }
        }
    }

    @Test
    fun `the tag is on the panel and pullable in every state where the recorder is stopped`() {
        for (status in ALL_STATUSES.filterNot(::isCapturing)) {
            for (pending in listOf(false, true)) {
                val switch = switchFor(status, pending)
                assertTrue(
                    "the standby tag must be shown in $status (pending=$pending)",
                    RemoveBeforeFlightTagPolicy.isOnPanel(status, switch),
                )
                assertTrue(
                    "the drag route must be offered in $status (pending=$pending) -- a failed " +
                        "start is exactly when the user wants to try again",
                    RemoveBeforeFlightTagPolicy.mayArm(switch),
                )
            }
        }
    }

    @Test
    fun `the transient 'switch already on, capture not yet running' state shows a passive tag`() {
        // Deliberately hand-built: mapEngineSwitchState cannot produce `checked = true` for Idle,
        // and that is the point. This is the defensive branch that makes isOnPanel deliberately
        // wider than mayArm -- if anything ever does put the switch on ahead of the capture, the
        // tag must degrade to the passive banner it used to be, not to a control that stops a
        // recording that is about to start.
        val checkedButIdle = EngineSwitchUiState(
            checked = true,
            enabled = true,
            pending = false,
            paused = false,
            error = null,
        )

        assertTrue(RemoveBeforeFlightTagPolicy.isOnPanel(CaptureStatus.Idle, checkedButIdle))
        assertFalse(
            "a tag shown while the switch reads 'on' must not be wired to onToggleEngine",
            RemoveBeforeFlightTagPolicy.mayArm(checkedButIdle),
        )
    }
}
