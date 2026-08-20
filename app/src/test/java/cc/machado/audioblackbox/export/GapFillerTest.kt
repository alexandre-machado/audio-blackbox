package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.AudioSnapshot
import cc.machado.audioblackbox.audio.PauseGap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [GapFiller] (issue #5): a snapshot with a simulated 90-second interruption
 * must produce a payload whose total duration equals the requested window, with silence at the
 * correct offset.
 */
class GapFillerTest {

    // bytesPerSecond = sampleRateHz * bytesPerSample(2) * channelCount(1) = 500*2*1 = 1000 bytes/sec.
    private val config = AudioConfig(sampleRateHz = 500, channelCount = 1)

    /** Builds raw audio bytes as a repeating counter pattern (0,1,2,...,255,0,1,...) so any byte
     * offset is independently identifiable -- silence (all zero bytes) is unambiguous against it. */
    private fun countingBytes(length: Int): ByteArray = ByteArray(length) { (it % 256).toByte() }

    @Test
    fun `90 second gap is filled as silence at the correct offset and total duration matches the window`() {
        // Window: 10 minutes requested. A 90s call happened 4 minutes into the window.
        val windowStart = 0L
        val requestedMillis = 10 * 60_000L // 600_000 ms
        val gapStart = 4 * 60_000L
        val gapDuration = 90_000L
        val gapEnd = gapStart + gapDuration

        // Raw audio actually captured: everything except the 90s gap, i.e.
        // (600_000 - 90_000) = 510_000 ms of continuous audio bytes.
        val rawAudioMillis = requestedMillis - gapDuration
        val rawAudioBytes = ((rawAudioMillis * config.bytesPerSecond) / 1000L).toInt()
        val rawData = countingBytes(rawAudioBytes)
        val snapshot = AudioSnapshot(rawData, windowStart)
        val gaps = listOf(PauseGap(gapStart, gapEnd))

        val result = GapFiller.fill(snapshot, gaps, config, requestedMillis)

        // Total duration invariant: within one frame of the requested window.
        val expectedBytes = ((requestedMillis * config.bytesPerSecond) / 1000L).toInt()
        assertTrue(
            "expected close to $expectedBytes bytes, got ${result.size}",
            Math.abs(result.size - expectedBytes) < config.bytesPerFrame,
        )

        // Silence sits at the right offset: bytes [gapStart*bytesPerSecond/1000, gapEnd*.../1000)
        // must all be zero, and must be exactly `gapDuration` worth of bytes.
        val gapOffsetBytes = ((gapStart * config.bytesPerSecond) / 1000L).toInt()
        val gapLengthBytes = ((gapDuration * config.bytesPerSecond) / 1000L).toInt()
        val silenceRegion = result.copyOfRange(gapOffsetBytes, gapOffsetBytes + gapLengthBytes)
        assertArrayEquals(ByteArray(gapLengthBytes), silenceRegion)

        // Bytes right before the silence match the raw counting pattern exactly (real audio, not
        // silence) and bytes right after it resume the pattern too -- proves the silence was
        // *inserted* at the gap, not that the whole tail from the gap onward was zeroed out (a
        // bug this same assertion catches: e.g. writing silence for the gap and everything after
        // it, instead of just the gap itself).
        assertArrayEquals(
            rawData.copyOfRange(0, gapOffsetBytes),
            result.copyOfRange(0, gapOffsetBytes),
        )
        assertArrayEquals(
            rawData.copyOfRange(gapOffsetBytes, rawData.size),
            result.copyOfRange(gapOffsetBytes + gapLengthBytes, result.size),
        )
    }

    @Test
    fun `gap entirely before the window contributes nothing`() {
        val rawData = countingBytes(1000) // 1 second at 1000 bytes/sec
        val snapshot = AudioSnapshot(rawData, 100_000L)
        val gaps = listOf(PauseGap(0L, 50_000L)) // ended long before the snapshot starts

        // Deliberately request 2ms more than the raw data's own duration (2ms, not 1ms, because
        // this config's bytesPerFrame is 2 -- an odd millisecond target frame-aligns back down to
        // the raw size and silently loses the headroom this test relies on). If clipping were
        // deleted, the (wrongly unclipped) gap would still land its silence at raw offset 0 --
        // coerceIn(rawPos, data.size) clamps the negative wall-clock delta to 0 regardless -- but
        // 50s worth of it, not 0. Trimming to a target *exactly equal* to the raw size (as the
        // previous version of this test did) always removes 100% of that leading silence,
        // regardless of how much there was, which is exactly why that version passed even with
        // clipping deleted. Requesting 2ms more than the raw size means 2 aligned bytes of that
        // erroneous silence survive the trim, changing both the size and the content of the
        // result -- the assertion below fails on a size mismatch alone if clipping is gone.
        val result = GapFiller.fill(snapshot, gaps, config, 1002L)

        assertArrayEquals(rawData, result)
    }

    @Test
    fun `gap partially overlapping the start of the window is clipped`() {
        val rawData = countingBytes(1000)
        val snapshot = AudioSnapshot(rawData, 100_000L)
        // Gap starts 500ms before the window and ends 200ms into it (700ms raw duration; only the
        // 200ms overlapping the window should ever become silence).
        val gaps = listOf(PauseGap(99_500L, 100_200L))

        // Deliberately request 2ms *more* than the correctly-clipped total (1000ms raw + 200ms
        // silence = 1200ms), not exactly 1200ms (2ms, not 1ms, because an odd millisecond target
        // frame-aligns back down to 1200 for this config's bytesPerFrame=2, silently losing the
        // headroom -- see the "entirely before" test above for the same trap). With clipping
        // intact, the output is only 1200 bytes long regardless (nothing to trim), so the extra
        // headroom changes nothing. With clipping deleted, the gap's full unclipped 700ms of
        // silence is inserted, then trimmed to the 1202-byte target -- which keeps 202 zero bytes,
        // not 200 -- so the exact expected array below only matches production code with clipping
        // present.
        val result = GapFiller.fill(snapshot, gaps, config, 1202L)

        val expected = ByteArray(200) + rawData
        assertArrayEquals(expected, result)
    }

    @Test
    fun `gap extending past the end of the window is clipped`() {
        val rawData = countingBytes(1000)
        val snapshot = AudioSnapshot(rawData, 0L)
        // Gap starts 800ms into the window (window is 1000ms of raw audio) and ends 500ms past
        // its end -- only the 200ms overlapping the window (800ms-1000ms) should become silence.
        val gaps = listOf(PauseGap(800L, 1500L))

        val result = GapFiller.fill(snapshot, gaps, config, 1201L)

        // real[0,800) | silence(200) | real[800,1000) -- the raw bytes at [800,1000) are still
        // written after the silence (they exist in the raw array; the gap only clips how much of
        // its *own* duration is treated as silence, it doesn't consume raw bytes).
        val expected = rawData.copyOfRange(0, 800) + ByteArray(200) + rawData.copyOfRange(800, 1000)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `two gaps place silence and raw audio at the correct offsets, not overshooting after the first gap`() {
        // Window: 0-23s of raw audio available, two interruptions inside a 30s requested window --
        // the "two phone calls" scenario. gap1 [10s,15s) is 5s, gap2 [20s,22s) is 2s.
        val windowStart = 0L
        val rawAudioMillis = 23_000L
        val rawData = countingBytes(((rawAudioMillis * config.bytesPerSecond) / 1000L).toInt())
        val snapshot = AudioSnapshot(rawData, windowStart)
        val gaps = listOf(
            PauseGap(startTimestampMillis = 10_000L, endTimestampMillis = 15_000L),
            PauseGap(startTimestampMillis = 20_000L, endTimestampMillis = 22_000L),
        )

        val result = GapFiller.fill(snapshot, gaps, config, 30_000L)

        // Expected output: real[0,10s) | silence(5s) | real[10s,15s) | silence(2s) | real[15s,23s).
        // The buggy formula (wall-clock delta straight into a raw offset, ignoring gap1's silence
        // already inserted) instead computes gap2's raw offset as 20s worth of raw bytes -- i.e.
        // real[10s,20s), 10s not 5s -- splicing 5s of the wrong raw audio in ahead of the second
        // silence and losing the last 5s of real audio entirely. This test fails with exactly
        // that mis-splice against the pre-fix arithmetic (verified by reverting the fix locally
        // and rerunning) and passes against the fixed offset math (subtracting the cumulative
        // duration of prior gaps before converting the wall-clock delta to a raw-array index).
        val bps = config.bytesPerSecond
        val expected = rawData.copyOfRange(0, 10 * bps) +
            ByteArray(5 * bps) +
            rawData.copyOfRange(10 * bps, 15 * bps) +
            ByteArray(2 * bps) +
            rawData.copyOfRange(15 * bps, 23 * bps)

        assertArrayEquals(expected, result)
    }

    @Test
    fun `gap offset that is not frame-aligned rounds down, never up`() {
        // bytesPerFrame = 2 (16-bit mono at this config). A gap starting at an odd millisecond
        // offset produces an odd, non-frame-aligned byte offset before alignment -- exercising
        // alignDown for real instead of only ever seeing already-aligned inputs (every other test
        // in this file uses whole-second/whole-frame boundaries, where alignDown is a no-op).
        val rawData = countingBytes(2000) // 2 seconds
        val snapshot = AudioSnapshot(rawData, 0L)
        // Raw offset before alignment: 501 bytes (odd) -> must round DOWN to 500, not up to 502.
        val gaps = listOf(PauseGap(startTimestampMillis = 501L, endTimestampMillis = 601L))

        val result = GapFiller.fill(snapshot, gaps, config, 2100L)

        // If rounded down (correct): real[0,500) | silence(100, aligned) | real[500,2000) -- the
        // raw bytes themselves are untouched by rounding, only the *insertion point* moves.
        // If rounded up (wrong): the insertion point would move to 502, so real[0,502) precedes
        // the silence instead of real[0,500) -- catching the direction of the rounding.
        val expected = rawData.copyOfRange(0, 500) + ByteArray(100) + rawData.copyOfRange(500, 2000)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `no gaps returns the raw snapshot unchanged, trimmed to the target window`() {
        val rawData = countingBytes(2000) // 2 seconds
        val snapshot = AudioSnapshot(rawData, 0L)

        val result = GapFiller.fill(snapshot, emptyList(), config, 1000L) // request only 1s

        assertEquals(1000, result.size)
        // Keeps the most recent (last) second, not the first.
        assertArrayEquals(rawData.copyOfRange(1000, 2000), result)
    }
}
