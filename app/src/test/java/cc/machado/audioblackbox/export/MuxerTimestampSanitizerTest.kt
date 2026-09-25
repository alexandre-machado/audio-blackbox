package cc.machado.audioblackbox.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #378: [MuxerTimestampSanitizer] is what stands between the encoder's timestamps and
 * `MPEG4Writer`'s out-of-order check.
 *
 * ## Oracle
 * [mpeg4WriterMarksMalformed] is a transcription of the two checks in AOSP
 * `MPEG4Writer::Track::threadEntry` (android16-release, `media/libstagefright/MPEG4Writer.cpp`)
 * that mark an audio track malformed: a negative timestamp, and a timestamp whose tick value
 * (`(ts * timescale + 500000) / 1000000`, timescale = sample rate for audio) is below the
 * previous one's ("do not support out of order frames"). A malformed track is the verified
 * mechanism behind `stop()`'s "muxer would have stopped already" with an empty sample table.
 * The model is applied to what the sanitizer *outputs*, never to the sanitizer's own formula, so
 * these tests fail if the sanitizer passes a regressing timestamp through (mutation-verified,
 * see the PR).
 */
class MuxerTimestampSanitizerTest {

    private val sampleRate = 44_100
    private val frameUs = (1024L * 1_000_000L + sampleRate - 1) / sampleRate // 23220

    @Test
    fun wellFormedTimestamps_passThroughUnchanged() {
        val sanitizer = MuxerTimestampSanitizer(sampleRate)
        val codecPts = List(50) { it * 1024L * 1_000_000L / sampleRate }

        val written = codecPts.map { sanitizer.next(it) }

        assertEquals(codecPts, written)
        assertEquals(0, sanitizer.corrections)
        assertNull(sanitizer.firstCorrection)
        assertTrue(!mpeg4WriterMarksMalformed(written, sampleRate))
    }

    @Test
    fun finalEosFrameStampedEarlier_isRewrittenToTheNextFrameSlot() {
        // The shape issue #378 suspects on the S25: regular frames, then the EOS-flagged final
        // frame stamped with an earlier time (e.g. the EOS input buffer's pts, which sits behind
        // the encoder's own frame counter once priming delay is included).
        val sanitizer = MuxerTimestampSanitizer(sampleRate)
        val regular = List(20) { it * 1024L * 1_000_000L / sampleRate }
        val eosPts = regular[17]
        assertTrue(
            "precondition: the raw sequence must be one MPEG4Writer rejects",
            mpeg4WriterMarksMalformed(regular + eosPts, sampleRate),
        )

        val written = regular.map { sanitizer.next(it) } + sanitizer.next(eosPts, isEndOfStream = true)

        assertTrue("sanitized output must pass MPEG4Writer's checks", !mpeg4WriterMarksMalformed(written, sampleRate))
        assertEquals(regular.last() + frameUs, written.last())
        assertEquals(1, sanitizer.corrections)
        val description = sanitizer.firstCorrection
        assertNotNull(description)
        assertTrue(description!!, description.contains("EOS-flagged"))
        assertTrue(description, description.contains("encoder pts ${eosPts}us"))
    }

    @Test
    fun finalFrameStampedZero_isRewritten() {
        val sanitizer = MuxerTimestampSanitizer(sampleRate)
        val raw = List(10) { it * frameUs } + 0L
        val written = raw.mapIndexed { i, pts -> sanitizer.next(pts, isEndOfStream = i == raw.lastIndex) }

        assertTrue(!mpeg4WriterMarksMalformed(written, sampleRate))
        assertEquals(9 * frameUs + frameUs, written.last())
    }

    @Test
    fun repeatedTimestamp_isAdvancedByOneFrame() {
        val sanitizer = MuxerTimestampSanitizer(sampleRate)
        val written = listOf(0L, frameUs, frameUs, 2 * frameUs).map { sanitizer.next(it) }

        assertEquals(listOf(0L, frameUs, 2 * frameUs, 3 * frameUs), written)
        assertEquals(2, sanitizer.corrections)
    }

    @Test
    fun negativeFirstTimestamp_isClampedToZero() {
        // MediaMuxer.writeSampleData rejects a negative presentationTimeUs outright.
        val sanitizer = MuxerTimestampSanitizer(sampleRate)
        val written = listOf(-5_000L, frameUs, 2 * frameUs).map { sanitizer.next(it) }

        assertEquals(0L, written.first())
        assertTrue(!mpeg4WriterMarksMalformed(written, sampleRate))
        assertEquals(1, sanitizer.corrections)
    }

    @Test
    fun endOfStreamMarker_isOneFrameAfterTheLastSample() {
        val sanitizer = MuxerTimestampSanitizer(sampleRate)
        sanitizer.next(0L)
        sanitizer.next(frameUs)
        sanitizer.next(frameUs - 1, isEndOfStream = true)

        assertEquals(2 * frameUs, sanitizer.lastPtsUs)
        assertEquals(3 * frameUs, sanitizer.endOfStreamMarkerPtsUs())
        assertEquals(0L, sanitizer.firstPtsUs)
        assertEquals(3, sanitizer.samples)
    }

    @Test
    fun frameDuration_neverRoundsToZeroTicks_atAnySupportedRate() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 48_000)) {
            val sanitizer = MuxerTimestampSanitizer(rate)
            val written = listOf(0L, 0L, 0L, 0L).map { sanitizer.next(it) }
            val ticks = written.map { (it * rate + 500_000L) / 1_000_000L }
            assertTrue("rate $rate: ticks $ticks must strictly increase", ticks.zipWithNext().all { (a, b) -> b > a })
        }
    }

    /** See the class doc: AOSP MPEG4Writer's malformed-track checks for an audio track. */
    private fun mpeg4WriterMarksMalformed(ptsUs: List<Long>, timescale: Int): Boolean {
        var lastTimestampUs = 0L
        for (ts in ptsUs) {
            if (ts < 0) return true
            val currDurationTicks =
                (ts * timescale + 500_000L) / 1_000_000L - (lastTimestampUs * timescale + 500_000L) / 1_000_000L
            if (currDurationTicks < 0) return true
            lastTimestampUs = ts
        }
        return false
    }
}
