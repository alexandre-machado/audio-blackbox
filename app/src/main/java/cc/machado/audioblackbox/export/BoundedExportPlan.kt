package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.ReadSinceResult
import java.io.IOException

/**
 * One segment of a bounded export's output stream, in ring-buffer cursor space rather than array
 * space (issue #72). A [Raw] segment is real PCM that must be read from the ring buffer via
 * [cc.machado.audioblackbox.audio.RingBuffer.readSince]; a [Silence] segment is wall-clock gap
 * filling that never touches the ring buffer at all. Segments are always emitted oldest-first, so
 * concatenating every segment's bytes in order reproduces exactly what [GapFiller.fill] would have
 * returned for the same inputs -- see [BoundedExportPlanner]'s doc for why the two are provably
 * the same computation, just staged differently.
 */
sealed interface PlanSegment {
    val length: Long

    data class Raw(val cursorStart: Long, override val length: Long) : PlanSegment
    data class Silence(override val length: Long) : PlanSegment
}

/** The fully-resolved shape of one bounded export: [segments] to read/synthesize in order, and
 * [totalOutputBytes] -- the exact sum of every segment's length, needed up front by formats (WAV)
 * whose header declares the payload size before any payload bytes are written. */
data class BoundedExportPlan(
    val segments: List<PlanSegment>,
    val totalOutputBytes: Long,
)

/**
 * Computes a [BoundedExportPlan] for one bounded "save the past" export without ever touching PCM
 * bytes (issue #72) -- every input here is a timestamp, a cursor, or a byte count, which is why
 * this can run entirely before the ring buffer is read even once.
 *
 * ## Why this is the same computation as [GapFiller.fill], just staged differently
 * [GapFiller.fill] walks a raw PCM array, splicing in silence at gap offsets and trimming the
 * front to fit [AudioConfig] and a target duration. Every decision it makes (where a gap's raw
 * offset falls, how much silence to insert, how many leading bytes to drop for the target-size
 * trim) is derived purely from wall-clock timestamps and byte-rate arithmetic -- never from the
 * PCM bytes' *values*. So the same decisions can be made first, as a plan over cursor ranges, and
 * the PCM read deferred until a chunk is actually needed ([BoundedExportReader]). This is what
 * lets the bounded export path avoid ever allocating a destination array proportional to the whole
 * retention window: [GapFiller.fill] is kept, untouched and still tested, purely as the *oracle*
 * this class's output is checked against (see `BoundedExportEquivalenceTest`), not because
 * anything in production still calls it.
 */
object BoundedExportPlanner {

    fun plan(
        startCursor: Long,
        rawLength: Long,
        windowStart: Long,
        gaps: List<PauseGap>,
        config: AudioConfig,
        targetDurationMillis: Long,
    ): BoundedExportPlan {
        require(startCursor >= 0) { "startCursor must not be negative, was $startCursor" }
        require(rawLength >= 0) { "rawLength must not be negative, was $rawLength" }
        require(targetDurationMillis >= 0) {
            "targetDurationMillis must not be negative, was $targetDurationMillis"
        }
        val bytesPerSecond = config.bytesPerSecond
        val bytesPerFrame = config.bytesPerFrame
        val windowEnd = windowStart + millisFor(rawLength, bytesPerSecond)

        val relevantGaps = gaps
            .map {
                PauseGap(
                    startTimestampMillis = it.startTimestampMillis.coerceIn(windowStart, windowEnd),
                    endTimestampMillis = it.endTimestampMillis.coerceIn(windowStart, windowEnd),
                )
            }
            .filter { it.endTimestampMillis > it.startTimestampMillis }
            .sortedBy { it.startTimestampMillis }

        val rawSegments = mutableListOf<PlanSegment>()
        var rawPos = 0L
        var cumulativeGapMillis = 0L
        for (gap in relevantGaps) {
            val wallClockDeltaMillis = gap.startTimestampMillis - windowStart - cumulativeGapMillis
            var rawOffset = alignDown(bytesFor(wallClockDeltaMillis, bytesPerSecond), bytesPerFrame)
            rawOffset = rawOffset.coerceIn(rawPos, rawLength)
            if (rawOffset > rawPos) {
                rawSegments += PlanSegment.Raw(startCursor + rawPos, rawOffset - rawPos)
            }
            rawPos = rawOffset

            val silenceBytes = alignDown(bytesFor(gap.durationMillis, bytesPerSecond), bytesPerFrame)
            if (silenceBytes > 0) rawSegments += PlanSegment.Silence(silenceBytes)
            cumulativeGapMillis += gap.durationMillis
        }
        if (rawLength > rawPos) {
            rawSegments += PlanSegment.Raw(startCursor + rawPos, rawLength - rawPos)
        }

        val filledLength = rawSegments.sumOf { it.length }
        val targetBytes = alignDown(bytesFor(targetDurationMillis, bytesPerSecond), bytesPerFrame)

        val trimmedSegments = if (filledLength > targetBytes) {
            dropLeading(rawSegments, filledLength - targetBytes)
        } else {
            rawSegments
        }

        return BoundedExportPlan(
            segments = trimmedSegments,
            totalOutputBytes = trimmedSegments.sumOf { it.length },
        )
    }

    /** Drops [excess] bytes from the front of [segments] (matching [GapFiller.fill]'s "keep the
     * most recent target bytes" trim), shrinking or removing leading segments as needed without
     * ever materializing their bytes. */
    private fun dropLeading(segments: List<PlanSegment>, excess: Long): List<PlanSegment> {
        var remainingToDrop = excess
        val result = mutableListOf<PlanSegment>()
        for (segment in segments) {
            if (remainingToDrop <= 0L) {
                result += segment
                continue
            }
            if (segment.length <= remainingToDrop) {
                remainingToDrop -= segment.length
                continue // this whole segment is dropped
            }
            val kept = segment.length - remainingToDrop
            remainingToDrop = 0L
            result += when (segment) {
                is PlanSegment.Raw -> PlanSegment.Raw(segment.cursorStart + (segment.length - kept), kept)
                is PlanSegment.Silence -> PlanSegment.Silence(kept)
            }
        }
        return result
    }

    private fun bytesFor(millis: Long, bytesPerSecond: Int): Long =
        ((millis * bytesPerSecond) / MILLIS_PER_SECOND).coerceAtLeast(0L)

    private fun millisFor(bytes: Long, bytesPerSecond: Int): Long =
        (bytes * MILLIS_PER_SECOND) / bytesPerSecond

    private fun alignDown(bytes: Long, bytesPerFrame: Int): Long = bytes - (bytes % bytesPerFrame)

    private const val MILLIS_PER_SECOND = 1000L
}

/** Thrown when a [BoundedExportReader] discovers, mid-drain, that the ring buffer no longer holds
 * a byte range the plan committed to reading (issue #72 / #29: a save must never silently
 * truncate into a shorter file that looks complete -- this makes that failure visible by aborting
 * the whole export instead). */
sealed class BoundedExportDrainException(
    val reason: ExportFailureReason,
    message: String,
) : IOException(message) {

    class CursorLapped(requestedCursor: Long, oldestAvailableCursor: Long, lostBytes: Long) :
        BoundedExportDrainException(
            ExportFailureReason.CURSOR_LAPPED,
            "bounded export drain fell behind: cursor $requestedCursor lapped, " +
                "$lostBytes bytes lost, oldest available is now $oldestAvailableCursor",
        )

    class StreamWasReset(requestedCursor: Long, currentCursor: Long) :
        BoundedExportDrainException(
            ExportFailureReason.STREAM_RESET,
            "bounded export drain's cursor $requestedCursor was reset under it (current cursor " +
                "$currentCursor) -- capture buffer was cleared mid-export",
        )

    class CaptureStopped(cursor: Long) :
        BoundedExportDrainException(
            ExportFailureReason.NO_AUDIO_BUFFERED,
            "capture stopped while draining the bounded export at cursor $cursor",
        )
}

/**
 * Bounded [PayloadChunkSource] over a [BoundedExportPlan]: pulls PCM for [PlanSegment.Raw] ranges
 * from [readSinceProvider] (`RingBuffer.readSince`) in chunks of at most [chunkSizeBytes], and
 * synthesizes zero-filled chunks for [PlanSegment.Silence] ranges -- never allocating a buffer
 * proportional to the plan's total length. This is the whole point of the plan/reader split (issue
 * #72): peak extra allocation here is O(chunkSizeBytes), independent of the retention window.
 *
 * Lapped/reset detection ([BoundedExportDrainException]) mirrors
 * [cc.machado.audioblackbox.export.ForwardRecordingEngine]'s drain loop exactly -- same primitive,
 * same failure shapes, deliberately not a second divergent protocol (see [RingBuffer.readSince]'s
 * doc and this repo's issue #47 decision record).
 *
 * "Stop means stop" residue discipline: each chunk returned is zeroed the moment the *next* call
 * (or [close]) proves it is no longer needed, so no live-mic PCM outlives its use in this class's
 * own state any longer than necessary.
 */
class BoundedExportReader(
    private val plan: BoundedExportPlan,
    private val readSinceProvider: (cursor: Long, maxBytes: Int) -> ReadSinceResult?,
    private val chunkSizeBytes: Int,
) : PayloadChunkSource {

    private var segmentIndex = 0
    private var cursorInSegment: Long = plan.segments.firstOrNull()?.let { segmentStart(it) } ?: 0L
    private var remainingInSegment: Long = plan.segments.firstOrNull()?.length ?: 0L
    private var lastChunk: ByteArray? = null

    init {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be positive, was $chunkSizeBytes" }
    }

    override fun nextChunk(): ByteArray? {
        zeroLastChunk()
        while (segmentIndex < plan.segments.size) {
            if (remainingInSegment <= 0L) {
                segmentIndex++
                if (segmentIndex < plan.segments.size) {
                    val next = plan.segments[segmentIndex]
                    cursorInSegment = segmentStart(next)
                    remainingInSegment = next.length
                }
                continue
            }
            val take = minOf(remainingInSegment, chunkSizeBytes.toLong()).toInt()
            val chunk = when (val segment = plan.segments[segmentIndex]) {
                is PlanSegment.Raw -> readRawChunk(cursorInSegment, take)
                is PlanSegment.Silence -> ByteArray(take)
            }
            cursorInSegment += take
            remainingInSegment -= take
            lastChunk = chunk
            return chunk
        }
        return null
    }

    /** Zeroes any chunk this reader handed out that the caller can no longer need -- call after
     * the encode loop exits (success, failure, or cancellation) so a partially-consumed chunk
     * never outlives this reader. */
    fun close() {
        zeroLastChunk()
    }

    private fun zeroLastChunk() {
        lastChunk?.let { java.util.Arrays.fill(it, 0) }
        lastChunk = null
    }

    private fun readRawChunk(cursor: Long, length: Int): ByteArray {
        when (val result = readSinceProvider(cursor, length)) {
            null -> throw BoundedExportDrainException.CaptureStopped(cursor)
            is ReadSinceResult.Lapped -> throw BoundedExportDrainException.CursorLapped(
                result.requestedCursor,
                result.oldestAvailableCursor,
                result.lostBytes,
            )
            is ReadSinceResult.StreamReset -> throw BoundedExportDrainException.StreamWasReset(
                result.requestedCursor,
                result.currentCursor,
            )
            is ReadSinceResult.Data -> {
                // This range was already fully written before the plan was built (it is strictly
                // behind the write cursor the plan was computed against), so a short read here can
                // only mean the reader fell behind and got lapped -- which the branch above already
                // catches via the sealed result, not via a short Data. Defensive check anyway: never
                // silently accept less than asked for (issue #29).
                check(result.bytes.size == length) {
                    "bounded export drain expected $length bytes at cursor $cursor, got " +
                        "${result.bytes.size} (nextCursor=${result.nextCursor})"
                }
                return result.bytes
            }
        }
    }

    private fun segmentStart(segment: PlanSegment): Long = when (segment) {
        is PlanSegment.Raw -> segment.cursorStart
        is PlanSegment.Silence -> 0L // unused for Silence; cursorInSegment only matters for Raw
    }
}
