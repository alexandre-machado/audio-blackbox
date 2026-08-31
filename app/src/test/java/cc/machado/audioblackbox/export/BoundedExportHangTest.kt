package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.FormatSegment
import cc.machado.audioblackbox.audio.PauseGap
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedExportHangTest {

    @Test(timeout = 2000L) // Fail loudly if it loops indefinitely
    fun `BoundedExportPlanner does not loop infinitely when truncating tiny gaps`() {
        val targetConfig = AudioConfig(sampleRateHz = 44100, channelCount = 1)
        val rangeBytesPerSecond = targetConfig.bytesPerSecond
        
        // Simulating the bug condition:
        // A gap starts EXACTLY 1ms after the export window start.
        val windowStart = 0L
        val gaps = listOf(
            PauseGap(startTimestampMillis = 1L, endTimestampMillis = 1000L)
        )
        
        // We set up a huge segment so availableBytes is large.
        // Since msUntilGap = 1, bytesFor(1) = 88 bytes.
        // If currentWallClock is not advanced properly, it will loop indefinitely.
        val startCursor = 0L
        val rawLength = 1_000_000L
        val segments = listOf(
            FormatSegment(0L, targetConfig)
        )

        val plan = BoundedExportPlanner.plan(
            startCursor = startCursor,
            rawLength = rawLength,
            windowStart = windowStart,
            gaps = gaps,
            segments = segments,
            targetConfig = targetConfig,
            targetDurationMillis = 5 * 60_000L
        )

        assertEquals(3, plan.segments.size)
        // First segment should be 88 bytes
        assertEquals(88L, (plan.segments[0] as PlanSegment.Raw).length)
        // Second segment should be silence bytes for the gap (999 ms)
        val silenceBytes = ((999L * targetConfig.bytesPerSecond) / 1000L) - (((999L * targetConfig.bytesPerSecond) / 1000L) % targetConfig.bytesPerFrame)
        assertEquals(silenceBytes, (plan.segments[1] as PlanSegment.Silence).length)
        
        // The remaining bytes
        val remainingBytes = 1_000_000L - 88L
        assertEquals(remainingBytes, (plan.segments[2] as PlanSegment.Raw).length)
    }
}
