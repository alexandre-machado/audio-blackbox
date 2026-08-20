package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.AudioSnapshot
import cc.machado.audioblackbox.audio.PauseGap
import java.io.ByteArrayOutputStream

/**
 * Reinserts wall-clock silence for recorded [PauseGap]s into a raw [AudioSnapshot], so the
 * exported timeline matches real elapsed time instead of being shorter than requested (issue
 * #5). Pure JVM, no Android dependency, so it is unit-testable without a device.
 *
 * ## Why this is needed
 * [cc.machado.audioblackbox.audio.RingBuffer.write] is simply not called while
 * [cc.machado.audioblackbox.audio.AudioCaptureEngine] is paused (phone call, mic preempted), so
 * the buffer holds zero bytes for that stretch of wall-clock time -- there is no silence to
 * "find" inside [AudioSnapshot.data], only a gap in coverage recorded separately as a
 * [PauseGap]. A raw snapshot's byte count therefore reflects audio-time, not wall-clock time:
 * if a 30-minute window contained a 90-second call, the raw snapshot is at most
 * `30min - 90s` worth of *bytes*, even though 30 real minutes elapsed. [ExportEngine] compensates
 * by requesting extra raw audio up front (see its `paddingMillis`); this class's job is placing
 * the silence at the right offset and trimming the result back to the requested window.
 *
 * ## Offset math
 * A gap's position in the *raw* (pre-fill) snapshot is `(gap.startTimestampMillis -
 * snapshot.startTimestampMillis - cumulativeDurationOfEarlierGaps) * bytesPerSecond / 1000`,
 * aligned down to a frame boundary ([AudioConfig.bytesPerFrame]) -- writing silence at a
 * non-frame-aligned offset would split a multi-byte sample or a stereo pair, corrupting every
 * sample after it.
 *
 * The `cumulativeDurationOfEarlierGaps` subtraction matters because [AudioSnapshot.data] is a
 * *compressed* stream: no bytes exist in it for time spent paused, so every second consumed by an
 * earlier gap must be subtracted back out of the wall-clock delta before it is converted into a
 * raw-array index. Skipping that subtraction (using the raw wall-clock delta directly) is correct
 * for the first gap only -- from the second gap onward it overshoots by the running sum of prior
 * gaps' silence bytes, splicing the wrong raw bytes into the output.
 *
 * Gaps that start before the snapshot window or end after it are clipped to the window first, so
 * only the overlapping portion is injected. Gaps entirely outside the window contribute nothing.
 */
object GapFiller {

    /**
     * Returns [snapshot]'s payload with silence inserted for every [gaps] entry that overlaps
     * it, then trimmed (dropping the oldest bytes first) to at most
     * `targetDurationMillis` worth of audio -- the requested export window. If less audio+silence
     * than that is available (a young session, or an incomplete gap list), the shorter result is
     * returned as-is; this mirrors [cc.machado.audioblackbox.audio.RingBuffer.snapshot]'s own
     * "never an error, just less" contract.
     */
    fun fill(
        snapshot: AudioSnapshot,
        gaps: List<PauseGap>,
        config: AudioConfig,
        targetDurationMillis: Long,
    ): ByteArray {
        require(targetDurationMillis >= 0) {
            "targetDurationMillis must not be negative, was $targetDurationMillis"
        }
        val bytesPerSecond = config.bytesPerSecond
        val bytesPerFrame = config.bytesPerFrame
        val data = snapshot.data
        val windowStart = snapshot.startTimestampMillis
        val windowEnd = windowStart + millisFor(data.size.toLong(), bytesPerSecond)

        val relevantGaps = gaps
            .map {
                PauseGap(
                    startTimestampMillis = it.startTimestampMillis.coerceIn(windowStart, windowEnd),
                    endTimestampMillis = it.endTimestampMillis.coerceIn(windowStart, windowEnd),
                )
            }
            .filter { it.endTimestampMillis > it.startTimestampMillis }
            .sortedBy { it.startTimestampMillis }

        val out = ByteArrayOutputStream(data.size)
        var rawPos = 0
        var cumulativeGapMillis = 0L
        for (gap in relevantGaps) {
            // The raw snapshot is a *compressed* stream -- it has no bytes for time spent
            // paused. So a raw-array offset must be measured against wall-clock time with every
            // prior gap's duration subtracted back out first; only then does it land on the same
            // byte that the wall-clock delta would reach in the (uncompressed) output stream.
            val wallClockDeltaMillis = gap.startTimestampMillis - windowStart - cumulativeGapMillis
            var rawOffset = alignDown(bytesFor(wallClockDeltaMillis, bytesPerSecond), bytesPerFrame)
            rawOffset = rawOffset.coerceIn(rawPos, data.size)
            out.write(data, rawPos, rawOffset - rawPos)
            rawPos = rawOffset

            val silenceBytes = alignDown(bytesFor(gap.durationMillis, bytesPerSecond), bytesPerFrame)
            if (silenceBytes > 0) out.write(ByteArray(silenceBytes))
            cumulativeGapMillis += gap.durationMillis
        }
        out.write(data, rawPos, data.size - rawPos)

        val result = out.toByteArray()
        val targetBytes = alignDown(bytesFor(targetDurationMillis, bytesPerSecond), bytesPerFrame)
        return if (result.size > targetBytes) {
            // Keep the most recent targetBytes -- drop the oldest excess, not the newest -- then
            // zero the untrimmed copy: it's a full duplicate of raw PCM (including the excess
            // that didn't make the cut) that would otherwise sit unzeroed on the heap until GC.
            val trimmed = result.copyOfRange(result.size - targetBytes, result.size)
            java.util.Arrays.fill(result, 0)
            trimmed
        } else {
            result
        }
    }

    private fun bytesFor(millis: Long, bytesPerSecond: Int): Int =
        ((millis * bytesPerSecond) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)

    private fun millisFor(bytes: Long, bytesPerSecond: Int): Long =
        (bytes * MILLIS_PER_SECOND) / bytesPerSecond

    private fun alignDown(bytes: Int, bytesPerFrame: Int): Int = bytes - (bytes % bytesPerFrame)

    private const val MILLIS_PER_SECOND = 1000L
}
