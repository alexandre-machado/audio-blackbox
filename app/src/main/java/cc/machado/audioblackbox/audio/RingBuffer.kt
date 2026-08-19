package cc.machado.audioblackbox.audio

/** Most recent audio pulled out of a [RingBuffer], oldest byte first. */
data class AudioSnapshot(
    val data: ByteArray,
    /** Wall-clock time (epoch millis) that [data]'s first byte was captured. */
    val startTimestampMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSnapshot) return false
        return data.contentEquals(other.data) && startTimestampMillis == other.startTimestampMillis
    }

    override fun hashCode(): Int = 31 * data.contentHashCode() + startTimestampMillis.hashCode()
}

/**
 * Fixed-size, pre-allocated circular buffer of PCM bytes. The single source of truth every
 * other module reads from: [AudioCaptureEngine] is the only writer, exporters and UI are
 * readers.
 *
 * Pure JVM class -- no Android dependency -- so it is testable with plain local unit tests, no
 * Robolectric, no instrumentation.
 *
 * ## Allocation
 * The backing array is allocated exactly once, at construction, to [capacityBytes]. [write]
 * never grows it and never allocates on its hot path: it only does bounds arithmetic and
 * `System.arraycopy` into the pre-allocated array plus pre-allocated marker arrays (see below).
 * When full, [write] silently overwrites the oldest bytes; it never throws on overflow.
 *
 * ## Per-frame timing
 * Every [write] call records the wall-clock time it happened at, in a small pre-allocated
 * ring of "markers" (parallel `LongArray`s of stream-byte-offset and timestamp, capped at
 * [MARKER_CAPACITY] entries). [snapshot] uses the nearest marker plus [bytesPerSecond] to
 * derive the wall-clock start time of the returned window, interpolating/extrapolating at a
 * constant byte rate between markers. Markers degrade gracefully under the same discard rule
 * as the audio itself: once more than [MARKER_CAPACITY] writes have happened, older markers
 * are overwritten, which only ever costs derived-timestamp precision, never correctness --
 * there is always at least one marker within the buffered window to interpolate from.
 *
 * ## Thread safety
 * There is exactly one writer thread ([AudioCaptureEngine]'s capture thread) and readers call
 * [snapshot] from other threads (export, UI). All mutable state (the byte array, the marker
 * arrays, and the write counters) is guarded by a single intrinsic lock ([lock]) taken by both
 * [write] and [snapshot] via `synchronized`. A single lock with no nested/cross locking
 * anywhere in this class rules out deadlock by construction, and mutual exclusion rules out
 * torn reads: a [snapshot] never observes a byte range or marker that [write] is mid-copying.
 * Because audio chunks are small (typically tens of milliseconds of PCM) and reads are
 * infrequent relative to writes, contention on this lock is negligible in practice.
 */
class RingBuffer(
    val capacityBytes: Int,
    private val bytesPerSecond: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacityBytes > 0) { "capacityBytes must be positive, was $capacityBytes" }
        require(bytesPerSecond > 0) { "bytesPerSecond must be positive, was $bytesPerSecond" }
    }

    private val lock = Any()

    // Backing store, pre-allocated once. Never resized.
    private val data = ByteArray(capacityBytes)

    // Total bytes ever written (monotonic, unbounded); (totalWritten % capacityBytes) is the
    // next write position. Only ever mutated inside `synchronized(lock)`.
    private var totalWritten: Long = 0L

    // Fixed-size marker ring: parallel arrays, pre-allocated once, holding the stream-byte
    // offset and wall-clock timestamp of the most recent MARKER_CAPACITY write() calls.
    private val markerOffsets = LongArray(MARKER_CAPACITY)
    private val markerTimestamps = LongArray(MARKER_CAPACITY)
    private var markerCount = 0
    private var markerNextSlot = 0

    /** Bytes currently held in the buffer (<= [capacityBytes]). */
    fun bufferedBytes(): Long = synchronized(lock) { minOf(totalWritten, capacityBytes.toLong()) }

    /**
     * Copies [length] bytes from [source] starting at [offset] into the buffer, overwriting the
     * oldest bytes once full. Allocation-free: only bounds math, `System.arraycopy`, and writes
     * into the pre-allocated backing/marker arrays. No-ops on a zero-length write.
     */
    fun write(source: ByteArray, offset: Int = 0, length: Int = source.size) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size) {
            "invalid range: offset=$offset length=$length source.size=${source.size}"
        }
        if (length == 0) return
        val timestamp = clock()
        synchronized(lock) {
            var srcPos = offset
            var writeLen = length
            if (writeLen > capacityBytes) {
                // This single write is bigger than the whole buffer; only its tail survives.
                srcPos += writeLen - capacityBytes
                writeLen = capacityBytes
            }

            val streamOffset = totalWritten
            val pos = (streamOffset % capacityBytes).toInt()
            val firstPart = minOf(writeLen, capacityBytes - pos)
            System.arraycopy(source, srcPos, data, pos, firstPart)
            val secondPart = writeLen - firstPart
            if (secondPart > 0) {
                System.arraycopy(source, srcPos + firstPart, data, 0, secondPart)
            }

            markerOffsets[markerNextSlot] = streamOffset
            markerTimestamps[markerNextSlot] = timestamp
            markerNextSlot = (markerNextSlot + 1) % MARKER_CAPACITY
            if (markerCount < MARKER_CAPACITY) markerCount++

            totalWritten = streamOffset + writeLen
        }
    }

    /**
     * Returns the most recent [durationMillis] of audio, oldest byte first, handling
     * wrap-around transparently. Requesting more than is buffered returns everything buffered,
     * never an error. Returns an empty snapshot if nothing has been written yet.
     */
    fun snapshot(durationMillis: Long): AudioSnapshot {
        require(durationMillis >= 0) { "durationMillis must not be negative, was $durationMillis" }
        synchronized(lock) {
            val available = minOf(totalWritten, capacityBytes.toLong())
            if (available == 0L) return AudioSnapshot(ByteArray(0), clock())

            val requestedBytes = (durationMillis * bytesPerSecond) / MILLIS_PER_SECOND
            val length = minOf(requestedBytes, available).toInt().coerceAtLeast(0)
            val startOffset = totalWritten - length

            if (length == 0) return AudioSnapshot(ByteArray(0), estimateTimestampLocked(startOffset))

            val result = ByteArray(length)
            val startPos = (startOffset % capacityBytes).toInt()
            val firstPart = minOf(length, capacityBytes - startPos)
            System.arraycopy(data, startPos, result, 0, firstPart)
            val secondPart = length - firstPart
            if (secondPart > 0) {
                System.arraycopy(data, 0, result, firstPart, secondPart)
            }

            return AudioSnapshot(result, estimateTimestampLocked(startOffset))
        }
    }

    /** Must be called with [lock] held. Interpolates/extrapolates from the closest marker. */
    private fun estimateTimestampLocked(streamOffset: Long): Long {
        if (markerCount == 0) return clock()

        var bestIndex = -1
        var bestDistance = Long.MAX_VALUE
        for (i in 0 until markerCount) {
            val distance = Math.abs(markerOffsets[i] - streamOffset)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }

        val markerOffset = markerOffsets[bestIndex]
        val markerTimestamp = markerTimestamps[bestIndex]
        val deltaBytes = streamOffset - markerOffset
        val deltaMillis = (deltaBytes * MILLIS_PER_SECOND) / bytesPerSecond
        return markerTimestamp + deltaMillis
    }

    private companion object {
        const val MARKER_CAPACITY = 8192
        const val MILLIS_PER_SECOND = 1000L
    }
}
