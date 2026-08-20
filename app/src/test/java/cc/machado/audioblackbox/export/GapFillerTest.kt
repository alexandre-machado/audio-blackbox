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

        val result = GapFiller.fill(snapshot, gaps, config, 1000L)

        assertArrayEquals(rawData, result)
    }

    @Test
    fun `gap partially overlapping the start of the window is clipped`() {
        val rawData = countingBytes(1000)
        val snapshot = AudioSnapshot(rawData, 100_000L)
        // Gap starts 500ms before the window and ends 200ms into it.
        val gaps = listOf(PauseGap(99_500L, 100_200L))

        // Request enough of a window that nothing gets trimmed off the front afterward -- this
        // test is about clipping the *gap* to the window, not about the trim-to-target step
        // (covered separately below), so the target duration matches the full raw+silence content
        // (1000ms raw + 200ms silence = 1200ms).
        val result = GapFiller.fill(snapshot, gaps, config, 1200L)

        // Only the 200ms overlapping the window (200 bytes at 1000 bytes/sec) becomes silence,
        // at the very start of the output.
        assertArrayEquals(ByteArray(200), result.copyOfRange(0, 200))
        assertEquals(rawData[0], result[200])
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
