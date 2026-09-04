package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for issue #328: [BoundedExportPlanner] used to end the export window at
 * `windowStart + totalRawDurationMillis`, i.e. it advanced a **wall-clock** start by an
 * **audio-only** duration. The result lands short of the window's real wall-clock end by the whole
 * interruption time inside it, so any [PauseGap] past that point clamped to zero length and was
 * dropped -- no silence planned, and a file short by exactly the interruptions it exists to splice.
 *
 * Oracle for every test here: the planned output duration, and the shape of the planned segments.
 * Nothing measures elapsed real time, nothing sleeps (AGENTS.md §3) -- `plan` is a pure function of
 * its arguments, so these are exact equalities, not tolerances.
 *
 * All timings use a 16 kHz mono PCM-16 config: 32,000 B/s, so 1 ms of audio is exactly 32 bytes and
 * every duration below divides evenly into whole frames.
 */
class BoundedExportGapWindowTest {

    private val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1) // 32,000 B/s

    private fun bytesForMillis(millis: Long): Long = millis * 32L

    /**
     * The headline defect. Two interruptions inside one 20 s wall-clock window, of which only 12 s
     * is audio. The second gap sits past `windowStart + 12s`, which is where the old code believed
     * the window ended, so it was silently discarded and the export came out 4 s short.
     *
     * This is the CI failure's shape (issue #328: 18,048 ms declared against a 26,639 ms window,
     * short by exactly the two calls' 8,591 ms), reproduced at Tier 0 in the pure planner.
     */
    @Test
    fun `a gap past the audio-only window end is still planned as silence, not dropped`() {
        val windowStart = 1_000_000L
        // 20 s of wall clock: 12 s of audio with two 4 s interruptions spliced into it.
        val gaps = listOf(
            PauseGap(windowStart + 6_000L, windowStart + 10_000L),
            // Starts at +14 s, past the +12 s "end" the audio-only arithmetic computes.
            PauseGap(windowStart + 14_000L, windowStart + 18_000L),
        )

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps,
            config = config,
            targetDurationMillis = 30_000L, // well above the window, so nothing is trimmed
        )

        assertEquals(
            "every gap in the window must be planned as silence; a gap beyond " +
                "windowStart + audio-only duration was dropped (issue #328)",
            8_000L,
            plannedSilenceMillis(plan),
        )
        assertEquals(
            "planned output must cover the whole wall-clock window (12s audio + 8s gaps)",
            20_000L,
            plannedDurationMillis(plan),
        )
        assertEquals(
            "silence must be spliced where the interruptions happened, not appended",
            listOf(
                PlanSegment.Raw(0L, bytesForMillis(6_000L), config),
                PlanSegment.Silence(bytesForMillis(4_000L)),
                PlanSegment.Raw(bytesForMillis(6_000L), bytesForMillis(4_000L), config),
                PlanSegment.Silence(bytesForMillis(4_000L)),
                PlanSegment.Raw(bytesForMillis(10_000L), bytesForMillis(2_000L), config),
            ),
            plan.segments,
        )
        assertEquals(bytesForMillis(20_000L), plan.totalOutputBytes)
    }

    /**
     * The self-reinforcing part of #328: each dropped gap pushed the believed end further below
     * reality, so later gaps were likelier to fall outside too. Six 2 s interruptions across one
     * window -- under the old arithmetic only the first three survived.
     *
     * The sixth interruption begins exactly as the buffered audio runs out (the user resumed and
     * saved straight away), so it also covers the planner's trailing-gap flush with real audio in
     * the plan ahead of it.
     */
    @Test
    fun `later gaps stay in the window even after earlier gaps have consumed wall-clock time`() {
        val windowStart = 1_000_000L
        // 2 s of audio, then 2 s of gap, repeated six times: 12 s audio + 12 s gaps = 24 s window.
        val gaps = (0 until 6).map { i ->
            val gapStart = windowStart + (i + 1) * 2_000L + i * 2_000L
            PauseGap(gapStart, gapStart + 2_000L)
        }

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps,
            config = config,
            targetDurationMillis = 60_000L,
        )

        assertEquals(
            "gap time already spliced must extend the window end, or each dropped gap makes the " +
                "next likelier to be dropped too (issue #328)",
            12_000L,
            plannedSilenceMillis(plan),
        )
        assertEquals(24_000L, plannedDurationMillis(plan))
        assertEquals(
            "each interruption is a separate silence segment between the audio around it",
            6,
            plan.segments.count { it is PlanSegment.Silence },
        )
        assertEquals(
            "the last interruption starts exactly as the audio runs out, so it is planned by the " +
                "trailing-gap flush rather than inside the segment walk",
            PlanSegment.Silence(bytesForMillis(2_000L)),
            plan.segments.last(),
        )
    }

    /** Gaps supplied newest-first must plan identically -- the window end is order-independent. */
    @Test
    fun `gap ordering supplied by the caller does not change the plan`() {
        val windowStart = 1_000_000L
        val gaps = listOf(
            PauseGap(windowStart + 6_000L, windowStart + 10_000L),
            PauseGap(windowStart + 14_000L, windowStart + 18_000L),
        )

        val ascending = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps,
            config = config,
            targetDurationMillis = 30_000L,
        )
        val descending = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps.reversed(),
            config = config,
            targetDurationMillis = 30_000L,
        )

        assertEquals(ascending.segments, descending.segments)
    }

    /**
     * The widening must stay bounded. A gap that ended before the oldest buffered sample has no
     * audio left around it to splice, and one that begins after all the buffered audio has nothing
     * left to sit in front of; neither may inflate the export.
     */
    @Test
    fun `gaps outside the buffered window contribute no silence`() {
        val windowStart = 1_000_000L
        val gaps = listOf(
            // Entirely before the window: aged out of the ring buffer.
            PauseGap(windowStart - 9_000L, windowStart - 5_000L),
            // Begins after every buffered sample and every gap already accounted for.
            PauseGap(windowStart + 20_000L, windowStart + 23_000L),
        )

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps,
            config = config,
            targetDurationMillis = 30_000L,
        )

        assertEquals(0L, plannedSilenceMillis(plan))
        assertEquals(12_000L, plannedDurationMillis(plan))
    }

    /**
     * A gap that aged out of the buffer must be ignored, not merely contribute no silence: if it is
     * carried into the window arithmetic it has a negative length inside the window and pulls the
     * end *backwards*, which drops a later, entirely legitimate interruption -- #328's failure
     * again, by a different route. `AudioCaptureEngine.pruneExpiredGaps` prunes on retention, which
     * is a wider window than the buffered audio, so aged-out gaps do reach the planner.
     */
    @Test
    fun `a gap older than the window does not pull the window end backwards`() {
        val windowStart = 1_000_000L
        val gaps = listOf(
            PauseGap(windowStart - 9_000L, windowStart - 5_000L), // aged out of the buffer
            PauseGap(windowStart + 6_000L, windowStart + 10_000L),
            PauseGap(windowStart + 14_000L, windowStart + 18_000L),
        )

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps,
            config = config,
            targetDurationMillis = 30_000L,
        )

        assertEquals(
            "the stale gap must not consume any of the window the live interruptions need",
            8_000L,
            plannedSilenceMillis(plan),
        )
        assertEquals(20_000L, plannedDurationMillis(plan))
    }

    /** A gap straddling the start of the buffered window contributes only its inside portion. */
    @Test
    fun `a gap straddling the window start contributes only the part inside the window`() {
        val windowStart = 1_000_000L
        val gaps = listOf(PauseGap(windowStart - 3_000L, windowStart + 2_000L))

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps,
            config = config,
            targetDurationMillis = 30_000L,
        )

        assertEquals(2_000L, plannedSilenceMillis(plan))
        assertEquals(14_000L, plannedDurationMillis(plan))
        assertEquals(
            "the clipped gap is at the head of the window, so silence leads the plan",
            PlanSegment.Silence(bytesForMillis(2_000L)),
            plan.segments.first(),
        )
    }

    /**
     * With no audio buffered around it, a straddling gap is all the plan can contain -- the segment
     * walk never runs at all with no raw audio, so the gap has to survive the window arithmetic and
     * reach the trailing flush on its own.
     */
    @Test
    fun `a straddling gap with no buffered audio plans silence via the trailing flush`() {
        val windowStart = 1_000_000L

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = 0L,
            windowStart = windowStart,
            gaps = listOf(PauseGap(windowStart - 3_000L, windowStart + 2_000L)),
            config = config,
            targetDurationMillis = 30_000L,
        )

        assertEquals(listOf(PlanSegment.Silence(bytesForMillis(2_000L))), plan.segments)
        assertEquals(2_000L, plannedDurationMillis(plan))
    }

    /** Trimming to the requested duration still drops from the head, silence included. */
    @Test
    fun `a window longer than the requested duration is trimmed from the head`() {
        val windowStart = 1_000_000L
        val gaps = listOf(
            PauseGap(windowStart + 6_000L, windowStart + 10_000L),
            PauseGap(windowStart + 14_000L, windowStart + 18_000L),
        )

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = bytesForMillis(12_000L),
            windowStart = windowStart,
            gaps = gaps,
            config = config,
            targetDurationMillis = 15_000L,
        )

        assertEquals(
            "the 20s window must be trimmed to the requested 15s, not left long",
            15_000L,
            plannedDurationMillis(plan),
        )
        assertEquals(
            "trimming drops the oldest 5s, which is audio, leaving both interruptions",
            8_000L,
            plannedSilenceMillis(plan),
        )
    }

    private fun plannedDurationMillis(plan: BoundedExportPlan): Long = plan.segments.sumOf { seg ->
        when (seg) {
            is PlanSegment.Raw -> seg.length * 1000L / seg.config.bytesPerSecond
            is PlanSegment.Silence -> seg.length * 1000L / plan.targetConfig.bytesPerSecond
        }
    }

    private fun plannedSilenceMillis(plan: BoundedExportPlan): Long = plan.segments
        .filterIsInstance<PlanSegment.Silence>()
        .sumOf { it.length * 1000L / plan.targetConfig.bytesPerSecond }
}
