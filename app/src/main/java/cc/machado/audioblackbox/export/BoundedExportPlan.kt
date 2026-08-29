package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.FormatSegment
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.ReadSinceResult
import java.io.IOException

/**
 * One segment of a bounded export's output stream, in ring-buffer cursor space rather than array
 * space (issue #72). A [Raw] segment is real PCM that must be read from the ring buffer via
 * [cc.machado.audioblackbox.audio.RingBuffer.readSince] (recorded under [config]); a [Silence]
 * segment is wall-clock gap filling that never touches the ring buffer at all.
 */
sealed interface PlanSegment {
    val length: Long

    data class Raw(
        val cursorStart: Long,
        override val length: Long,
        val config: AudioConfig = AudioConfig(),
    ) : PlanSegment

    data class Silence(override val length: Long) : PlanSegment
}

/** The fully-resolved shape of one bounded export: [segments] to read/synthesize in order,
 * [totalOutputBytes] declared in [targetConfig], and [targetConfig] itself (issue #194). */
data class BoundedExportPlan(
    val segments: List<PlanSegment>,
    val totalOutputBytes: Long,
    val targetConfig: AudioConfig = AudioConfig(),
)

/**
 * Computes a [BoundedExportPlan] for one bounded "save the past" export without ever touching PCM
 * bytes (issue #72, multi-format segments in issue #194).
 */
object BoundedExportPlanner {

    fun plan(
        startCursor: Long,
        rawLength: Long,
        windowStart: Long,
        gaps: List<PauseGap>,
        config: AudioConfig,
        targetDurationMillis: Long,
    ): BoundedExportPlan = plan(
        startCursor = startCursor,
        rawLength = rawLength,
        windowStart = windowStart,
        gaps = gaps,
        segments = listOf(FormatSegment(startCursor, config)),
        targetConfig = config,
        targetDurationMillis = targetDurationMillis,
    )

    fun plan(
        startCursor: Long,
        rawLength: Long,
        windowStart: Long,
        gaps: List<PauseGap>,
        segments: List<FormatSegment>,
        targetConfig: AudioConfig,
        targetDurationMillis: Long,
    ): BoundedExportPlan {
        require(startCursor >= 0) { "startCursor must not be negative, was $startCursor" }
        require(rawLength >= 0) { "rawLength must not be negative, was $rawLength" }
        require(targetDurationMillis >= 0) {
            "targetDurationMillis must not be negative, was $targetDurationMillis"
        }

        val rawEndCursor = startCursor + rawLength
        val activeSegs = if (segments.isNotEmpty()) {
            segments.sortedBy { it.startOffset }
        } else {
            listOf(FormatSegment(startCursor, targetConfig))
        }

        // 1. Calculate raw sub-ranges across format segments
        data class RawSubRange(val start: Long, val end: Long, val config: AudioConfig)
        val subRanges = mutableListOf<RawSubRange>()
        for (i in activeSegs.indices) {
            val seg = activeSegs[i]
            val nextStart = if (i + 1 < activeSegs.size) activeSegs[i + 1].startOffset else rawEndCursor
            val rStart = maxOf(startCursor, seg.startOffset)
            val rEnd = minOf(rawEndCursor, nextStart)
            if (rEnd > rStart) {
                subRanges += RawSubRange(rStart, rEnd, seg.config)
            }
        }
        if (subRanges.isEmpty() && rawLength > 0L) {
            subRanges += RawSubRange(startCursor, rawEndCursor, targetConfig)
        }

        // Calculate total raw duration
        var totalRawDurationMillis = 0L
        for (sr in subRanges) {
            totalRawDurationMillis += millisFor(sr.end - sr.start, sr.config.bytesPerSecond)
        }

        val windowEnd = windowStart + totalRawDurationMillis
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
        var currentWallClock = windowStart
        var gapIndex = 0

        for (sr in subRanges) {
            var rangeCursor = sr.start
            val rangeBytesPerSecond = sr.config.bytesPerSecond
            val rangeBytesPerFrame = sr.config.bytesPerFrame

            while (rangeCursor < sr.end) {
                if (gapIndex < relevantGaps.size) {
                    val nextGap = relevantGaps[gapIndex]
                    if (nextGap.startTimestampMillis <= currentWallClock) {
                        // Gap is right now
                        val silenceBytes = alignDown(
                            bytesFor(nextGap.durationMillis, targetConfig.bytesPerSecond),
                            targetConfig.bytesPerFrame,
                        )
                        if (silenceBytes > 0) {
                            rawSegments += PlanSegment.Silence(silenceBytes)
                        }
                        currentWallClock += nextGap.durationMillis
                        gapIndex++
                        continue
                    }

                    val msUntilGap = nextGap.startTimestampMillis - currentWallClock
                    val bytesUntilGap = alignDown(bytesFor(msUntilGap, rangeBytesPerSecond), rangeBytesPerFrame)
                    val availableBytes = sr.end - rangeCursor
                    val takeBytes = minOf(availableBytes, bytesUntilGap)

                    if (takeBytes > 0) {
                        rawSegments += PlanSegment.Raw(rangeCursor, takeBytes, sr.config)
                        rangeCursor += takeBytes
                        currentWallClock += millisFor(takeBytes, rangeBytesPerSecond)
                    } else if (bytesUntilGap <= 0) {
                        // Reached gap boundary
                        val silenceBytes = alignDown(
                            bytesFor(nextGap.durationMillis, targetConfig.bytesPerSecond),
                            targetConfig.bytesPerFrame,
                        )
                        if (silenceBytes > 0) {
                            rawSegments += PlanSegment.Silence(silenceBytes)
                        }
                        currentWallClock += nextGap.durationMillis
                        gapIndex++
                    }
                } else {
                    val remainingBytes = sr.end - rangeCursor
                    if (remainingBytes > 0) {
                        rawSegments += PlanSegment.Raw(rangeCursor, remainingBytes, sr.config)
                        rangeCursor += remainingBytes
                        currentWallClock += millisFor(remainingBytes, rangeBytesPerSecond)
                    }
                }
            }
        }

        // Remaining gaps at the end
        while (gapIndex < relevantGaps.size) {
            val gap = relevantGaps[gapIndex++]
            val silenceBytes = alignDown(
                bytesFor(gap.durationMillis, targetConfig.bytesPerSecond),
                targetConfig.bytesPerFrame,
            )
            if (silenceBytes > 0) {
                rawSegments += PlanSegment.Silence(silenceBytes)
            }
        }

        // Calculate total output duration
        val totalDurationMillis = rawSegments.sumOf { seg ->
            when (seg) {
                is PlanSegment.Raw -> millisFor(seg.length, seg.config.bytesPerSecond)
                is PlanSegment.Silence -> millisFor(seg.length, targetConfig.bytesPerSecond)
            }
        }

        val trimmedSegments = if (totalDurationMillis > targetDurationMillis) {
            dropLeadingDuration(rawSegments, totalDurationMillis - targetDurationMillis, targetConfig)
        } else {
            rawSegments
        }

        val totalOutputBytes = trimmedSegments.sumOf { seg ->
            when (seg) {
                is PlanSegment.Raw -> {
                    val ms = millisFor(seg.length, seg.config.bytesPerSecond)
                    alignDown(bytesFor(ms, targetConfig.bytesPerSecond), targetConfig.bytesPerFrame)
                }
                is PlanSegment.Silence -> seg.length
            }
        }

        return BoundedExportPlan(
            segments = trimmedSegments,
            totalOutputBytes = totalOutputBytes,
            targetConfig = targetConfig,
        )
    }

    private fun dropLeadingDuration(
        segments: List<PlanSegment>,
        excessMillis: Long,
        targetConfig: AudioConfig,
    ): List<PlanSegment> {
        var remainingMsToDrop = excessMillis
        val result = mutableListOf<PlanSegment>()
        for (segment in segments) {
            if (remainingMsToDrop <= 0L) {
                result += segment
                continue
            }
            val segMs = when (segment) {
                is PlanSegment.Raw -> millisFor(segment.length, segment.config.bytesPerSecond)
                is PlanSegment.Silence -> millisFor(segment.length, targetConfig.bytesPerSecond)
            }
            if (segMs <= remainingMsToDrop) {
                remainingMsToDrop -= segMs
                continue
            }
            val keptMs = segMs - remainingMsToDrop
            remainingMsToDrop = 0L
            result += when (segment) {
                is PlanSegment.Raw -> {
                    val keptBytes = alignDown(
                        bytesFor(keptMs, segment.config.bytesPerSecond),
                        segment.config.bytesPerFrame,
                    )
                    val dropBytes = segment.length - keptBytes
                    PlanSegment.Raw(segment.cursorStart + dropBytes, keptBytes, segment.config)
                }
                is PlanSegment.Silence -> {
                    val keptBytes = alignDown(
                        bytesFor(keptMs, targetConfig.bytesPerSecond),
                        targetConfig.bytesPerFrame,
                    )
                    PlanSegment.Silence(keptBytes)
                }
            }
        }
        return result
    }

    private fun bytesFor(millis: Long, bytesPerSecond: Int): Long =
        ((millis * bytesPerSecond) / MILLIS_PER_SECOND).coerceAtLeast(0L)

    private fun millisFor(bytes: Long, bytesPerSecond: Int): Long =
        if (bytesPerSecond > 0) (bytes * MILLIS_PER_SECOND) / bytesPerSecond else 0L

    private fun alignDown(bytes: Long, bytesPerFrame: Int): Long = bytes - (bytes % bytesPerFrame)

    private const val MILLIS_PER_SECOND = 1000L
}

/** Thrown when a [BoundedExportReader] discovers, mid-drain, that the ring buffer no longer holds
 * a byte range the plan committed to reading (issue #72 / #29). */
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
 * converts them via [PcmAudioConverter] into [BoundedExportPlan.targetConfig] if needed (issue #194).
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
    private var currentConverter: PcmAudioConverter? = null
    private var lastSegmentIndex = -1
    private var pendingFlushedBytes: ByteArray? = null

    init {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be positive, was $chunkSizeBytes" }
    }

    override fun nextChunk(): ByteArray? {
        zeroLastChunk()

        val pending = pendingFlushedBytes
        if (pending != null && pending.isNotEmpty()) {
            pendingFlushedBytes = null
            lastChunk = pending
            return pending
        }

        while (segmentIndex < plan.segments.size) {
            if (remainingInSegment <= 0L) {
                val flushed = currentConverter?.flush()
                currentConverter = null
                segmentIndex++
                if (segmentIndex < plan.segments.size) {
                    val next = plan.segments[segmentIndex]
                    cursorInSegment = segmentStart(next)
                    remainingInSegment = next.length
                }
                if (flushed != null && flushed.isNotEmpty()) {
                    lastChunk = flushed
                    return flushed
                }
                continue
            }
            if (segmentIndex != lastSegmentIndex) {
                lastSegmentIndex = segmentIndex
                val seg = plan.segments[segmentIndex]
                currentConverter = if (seg is PlanSegment.Raw && (seg.config.sampleRateHz != plan.targetConfig.sampleRateHz || seg.config.channelCount != plan.targetConfig.channelCount)) {
                    PcmAudioConverter(seg.config, plan.targetConfig)
                } else {
                    null
                }
            }

            val take = minOf(remainingInSegment, chunkSizeBytes.toLong()).toInt()
            val rawChunk = when (val segment = plan.segments[segmentIndex]) {
                is PlanSegment.Raw -> readRawChunk(cursorInSegment, take)
                is PlanSegment.Silence -> ByteArray(take)
            }
            cursorInSegment += take
            remainingInSegment -= take

            val chunk = currentConverter?.convert(rawChunk) ?: rawChunk
            lastChunk = chunk
            return chunk
        }

        val finalFlushed = currentConverter?.flush()
        currentConverter = null
        if (finalFlushed != null && finalFlushed.isNotEmpty()) {
            lastChunk = finalFlushed
            return finalFlushed
        }

        return null
    }

    /** Zeroes any chunk this reader handed out that the caller can no longer need. */
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
        is PlanSegment.Silence -> 0L
    }
}

