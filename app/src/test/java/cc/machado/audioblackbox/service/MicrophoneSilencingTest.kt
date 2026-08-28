package cc.machado.audioblackbox.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the decision that `RecorderService.recordingCallback` drives (issue #155).
 *
 * Before this existed, that decision was four lines inside an anonymous
 * `AudioManager.AudioRecordingCallback` taking a framework `Parcelable`, reachable only from an
 * instrumented test -- and the only instrumented test that touched it (`InterruptionSpliceTest`)
 * drives a *telephony* interruption via `adb emu gsm call`. So the absent-session branch, the one
 * that could strand capture forever, had no coverage at all on any tier.
 */
class MicrophoneSilencingTest {

    private val ourSession = 42
    private val someoneElse = ActiveCapture(sessionId = 99, isSilenced = false)

    @Test
    fun `pauses when our own capture is reported silenced`() {
        val decision = MicrophoneSilencing.decide(
            ourSessionId = ourSession,
            active = listOf(someoneElse, ActiveCapture(ourSession, isSilenced = true)),
        )
        assertEquals(SilencingDecision.PAUSE, decision)
    }

    @Test
    fun `resumes when our own capture is present and not silenced`() {
        val decision = MicrophoneSilencing.decide(
            ourSessionId = ourSession,
            active = listOf(ActiveCapture(ourSession, isSilenced = false), someoneElse),
        )
        assertEquals(SilencingDecision.RESUME, decision)
    }

    /**
     * The regression this issue exists for. The previous implementation bailed out on an absent
     * session, which -- because that same line was the app's only `engine.resume()` call site --
     * left capture pinned in Paused with no way back short of the user restarting it by hand.
     */
    @Test
    fun `resumes rather than doing nothing when our session is absent from the list`() {
        val decision = MicrophoneSilencing.decide(
            ourSessionId = ourSession,
            active = listOf(someoneElse),
        )
        assertEquals(
            "an absent session must resolve to RESUME -- leaving it undecided is what stranded " +
                "capture in Paused indefinitely (issue #155)",
            SilencingDecision.RESUME,
            decision,
        )
    }

    @Test
    fun `resumes when the list is empty entirely`() {
        assertEquals(
            SilencingDecision.RESUME,
            MicrophoneSilencing.decide(ourSessionId = ourSession, active = emptyList()),
        )
    }

    /**
     * The strand, replayed as the sequence that produces it: silenced, then gone. The second
     * decision is the one that has to break the streak; if it comes back as anything other than
     * RESUME the engine never writes into the ring buffer again.
     */
    @Test
    fun `a silenced-then-absent sequence ends in RESUME, not a second PAUSE`() {
        val silenced = MicrophoneSilencing.decide(
            ourSessionId = ourSession,
            active = listOf(ActiveCapture(ourSession, isSilenced = true)),
        )
        val thenAbsent = MicrophoneSilencing.decide(ourSessionId = ourSession, active = emptyList())

        assertEquals(SilencingDecision.PAUSE, silenced)
        assertEquals(SilencingDecision.RESUME, thenAbsent)
    }

    /** Someone else being silenced is not our business -- matching on session id is what keeps
     * this from reacting to an unrelated app's contention. */
    @Test
    fun `ignores another app being silenced and resumes our own`() {
        val decision = MicrophoneSilencing.decide(
            ourSessionId = ourSession,
            active = listOf(
                ActiveCapture(sessionId = 99, isSilenced = true),
                ActiveCapture(ourSession, isSilenced = false),
            ),
        )
        assertEquals(SilencingDecision.RESUME, decision)
    }

    /**
     * A null session id means the engine holds no `AudioRecord` (Idle or Error). There is no
     * pause/resume state to have an opinion about, and returning RESUME here would be a
     * meaningless call into a stopped engine rather than a safe default.
     */
    @Test
    fun `reports NOT_CAPTURING when the engine holds no session`() {
        assertEquals(
            SilencingDecision.NOT_CAPTURING,
            MicrophoneSilencing.decide(ourSessionId = null, active = listOf(someoneElse)),
        )
    }

    @Test
    fun `reports NOT_CAPTURING with no session even when the list is empty`() {
        assertEquals(
            SilencingDecision.NOT_CAPTURING,
            MicrophoneSilencing.decide(ourSessionId = null, active = emptyList()),
        )
    }
}
