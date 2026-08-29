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
 * Outcome of [RingBuffer.readSince]. A sealed type on purpose (issue #51): the PCM only exists on
 * the [Data] branch, so there is no way to obtain bytes without having written a branch for the
 * failure cases. A caller cannot accidentally treat lost audio as "a poll that happened to return
 * fewer bytes" -- the two are different types, not different lengths of the same one. Silent audio
 * loss is the worst failure this product can have (issue #29: failure must be visible, never a
 * silent no-op), so the primitive refuses to express it as a clamped length.
 */
sealed interface ReadSinceResult {

    /**
     * PCM read successfully: [bytes] are exactly the stream bytes in `[startCursor, nextCursor)`,
     * oldest first, with no gap relative to the cursor that was asked for. Pass [nextCursor] to
     * the next [RingBuffer.readSince] call to continue the stream.
     *
     * [remainingBytes] is how much more was already written past [nextCursor] at the instant of
     * the read -- useful for a drain loop that wants to keep reading immediately instead of
     * waiting for its next tick. An empty [bytes] with `startCursor == nextCursor` simply means
     * nothing new has been written yet; it is a normal idle poll, not a failure.
     */
    class Data(
        val bytes: ByteArray,
        val startCursor: Long,
        val nextCursor: Long,
        val remainingBytes: Long,
    ) : ReadSinceResult {
        override fun toString(): String =
            "Data(bytes=${bytes.size}, startCursor=$startCursor, nextCursor=$nextCursor, " +
                "remainingBytes=$remainingBytes)"
    }

    /**
     * The caller fell behind: the ring overwrote bytes it had not read yet, so [lostBytes] bytes
     * of PCM starting at [requestedCursor] no longer exist anywhere and can never be recovered.
     * No audio is returned, deliberately -- returning "the part that survived" is precisely the
     * short read that would make the loss invisible.
     *
     * [oldestAvailableCursor] is the oldest byte still buffered, which is where a caller that
     * chooses to keep going (after surfacing the loss -- that is the consumer's job, issue #54)
     * should resume from.
     */
    data class Lapped(
        val requestedCursor: Long,
        val oldestAvailableCursor: Long,
        val lostBytes: Long,
    ) : ReadSinceResult

    /**
     * The cursor points past the end of the stream, which can only mean the stream restarted
     * under the caller: [RingBuffer.clear] resets the byte counter to zero (it is called on
     * `stop`, and on the save-then-restart-the-buffer flow). Reported rather than thrown so a
     * drain thread that outlives a `clear()` fails visibly without dying, and reported separately
     * from [Lapped] because it is a different event: nothing was overwritten out from under the
     * caller, the stream it was reading simply no longer exists. Resume from [currentCursor].
     *
     * ## Contract, and the precise limit of this detection (PR #86 review, `@rev` finding 2)
     * Detection is **positional**, not generational: the only evidence that a restart happened is
     * `cursor > totalWritten`. That evidence expires. Once the restarted stream has been written
     * past the stale cursor's offset -- `clear()` followed by more than `cursor` bytes of new
     * writes -- a drain still holding the old cursor falls through to the ordinary [Data] path and
     * receives **new-stream bytes at old-stream offsets**: gap-free to the caller, spliced across
     * a stop/start boundary in reality, with the tail of the old stream gone and no signal. So,
     * stated as a contract for whoever wires up a consumer:
     *
     * - A stale cursor is reported as [StreamReset] **only while the restarted stream is shorter
     *   than that cursor**. Past that point a cleared-and-rewritten buffer is indistinguishable
     *   from a continuing one.
     * - Therefore a caller **must not** treat "not [StreamReset]" as proof that no restart
     *   happened. Knowing whether the buffer was cleared is the consumer's responsibility (it is
     *   the side that triggers `clear()`), not something this primitive can be asked after the
     *   fact.
     *
     * This is not reachable through today's engine: `AudioCaptureEngine` allocates a fresh
     * `RingBuffer` per session and clears the old instance only in the capture thread's `finally`,
     * after which nothing writes to it again -- a stale drain there sees [StreamReset] forever.
     * The flow that would reach it is issue #47's C2 ("save the past, then restart the buffer"),
     * i.e. clearing a buffer that keeps being written. The durable fix is a generation counter
     * bumped by [RingBuffer.clear] and carried in the cursor; that is a real API change and
     * belongs to the issue that introduces the flow (#54), deliberately not done here so this
     * stays a primitive-only change. `RingBufferReadSinceTest` pins both directions of the
     * behaviour above so the limit is a tested, written contract rather than an assumption.
     */
    data class StreamReset(
        val requestedCursor: Long,
        val currentCursor: Long,
    ) : ReadSinceResult
}

/** Metadata descriptor for a contiguous range of audio captured under a specific format (issue #194). */
data class FormatSegment(
    val startOffset: Long,
    val config: AudioConfig,
)

/**
 * Fixed-size, pre-allocated circular buffer of PCM bytes. The single source of truth every
 * other module reads from: [AudioCaptureEngine] is the only writer, exporters and UI are
 * readers.
 *
 * Pure JVM class -- no Android dependency -- so it is testable with plain local unit tests, no
 * Robolectric, no instrumentation.
 *
 * ## Allocation & Resizing
 * The backing array is allocated at construction to [capacityBytes] and can be dynamically
 * resized in-place via [resize] (issue #223) without discarding buffered audio. [write] never
 * allocates on its hot path: it only does bounds arithmetic and `System.arraycopy` into the
 * pre-allocated array plus pre-allocated marker arrays (see below). When full, [write] silently
 * overwrites the oldest bytes; it never throws on overflow.
 *
 * ## Heterogeneous format segments (issue #194)
 * When audio capture quality presets change mid-stream, [RingBuffer] preserves existing audio
 * across the boundary without clearing the buffer. Each format change appends a [FormatSegment]
 * recording the stream offset and [AudioConfig]. Byte<->time math, snapshot duration lookup, and
 * timestamp estimation transparently interpolate across segments. As the ring wraps and overwrites
 * old bytes, stale segment descriptors are evicted so memory stays strictly bounded.
 *
 * ## Per-frame timing
 * Every [write] call records the wall-clock time it happened at, in a small pre-allocated
 * ring of "markers" (parallel `LongArray`s of stream-byte-offset and timestamp, capped at
 * [MARKER_CAPACITY] entries). [snapshot] uses the nearest marker plus segment format rates to
 * derive the wall-clock start time of the returned window.
 *
 * ## Thread safety
 * There is exactly one writer thread ([AudioCaptureEngine]'s capture thread) and readers call
 * [snapshot] from other threads (export, UI). All mutable state (the byte array, the marker
 * arrays, the segment list, and the write counters) is guarded by a single intrinsic lock ([lock]).
 */
class RingBuffer(
    capacityBytes: Int,
    val initialConfig: AudioConfig = AudioConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        capacityBytes: Int,
        bytesPerSecond: Int,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        capacityBytes = capacityBytes,
        initialConfig = AudioConfig(sampleRateHz = bytesPerSecond / 2, channelCount = 1),
        clock = clock,
    )

    init {
        require(capacityBytes > 0) { "capacityBytes must be positive, was $capacityBytes" }
        require(initialConfig.bytesPerSecond > 0) {
            "initialConfig.bytesPerSecond must be positive, was ${initialConfig.bytesPerSecond}"
        }
    }

    private val lock = Any()

    private var _capacityBytes: Int = capacityBytes

    /** Configured maximum capacity in bytes. */
    val capacityBytes: Int get() = synchronized(lock) { _capacityBytes }

    // Backing store, dynamically resizable via [resize] (issue #223).
    private var data = ByteArray(capacityBytes)

    // Total bytes ever written (monotonic, unbounded); (totalWritten % capacityBytes) is the
    // next write position. Only ever mutated inside `synchronized(lock)`.
    private var totalWritten: Long = 0L

    // Stream offset of the oldest byte that was ever preserved/retained across resizes (issue #223).
    private var baseStreamOffset: Long = 0L

    private fun oldestAvailableLocked(): Long =
        maxOf(baseStreamOffset, totalWritten - _capacityBytes.toLong())

    // Active format segments. Preserves format history across quality preset changes (issue #194).
    private val segments = mutableListOf(FormatSegment(startOffset = 0L, config = initialConfig))

    /** Active format of the write head. */
    val currentConfig: AudioConfig get() = synchronized(lock) { segments.last().config }

    /** Active bytes-per-second rate of the write head (backward-compatible mirror). */
    val bytesPerSecond: Int get() = synchronized(lock) { segments.last().config.bytesPerSecond }

    // Fixed-size marker ring: parallel arrays, pre-allocated once, holding the stream-byte
    // offset and wall-clock timestamp of the most recent MARKER_CAPACITY write() calls.
    private val markerOffsets = LongArray(MARKER_CAPACITY)
    private val markerTimestamps = LongArray(MARKER_CAPACITY)
    private var markerCount = 0
    private var markerNextSlot = 0

    /** Bytes currently held in the buffer (<= [capacityBytes]). */
    fun bufferedBytes(): Long = synchronized(lock) { totalWritten - oldestAvailableLocked() }

    /**
     * Resizes the ring buffer capacity in-place without discarding surviving audio (issue #223).
     *
     * When expanding ([newCapacityBytes] > [capacityBytes]): preserves 100% of currently buffered audio.
     * When shrinking ([newCapacityBytes] < [capacityBytes]): preserves the newest [newCapacityBytes] of
     * audio (FIFO truncation of the oldest bytes exceeding the new capacity), and prunes expired
     * format segments.
     *
     * Monotonic stream coordinates ([totalWritten]) remain continuous, ensuring readers
     * ([readSince], [snapshot]) continue seamlessly without gap or stream reset.
     */
    fun resize(newCapacityBytes: Int) {
        require(newCapacityBytes > 0) { "newCapacityBytes must be positive, was $newCapacityBytes" }
        synchronized(lock) {
            if (newCapacityBytes == _capacityBytes) return

            val oldCapacity = _capacityBytes
            val oldData = data
            val newData = ByteArray(newCapacityBytes)

            val oldOldest = oldestAvailableLocked()
            val availableBytes = totalWritten - oldOldest
            val bytesToKeep = minOf(availableBytes, newCapacityBytes.toLong()).toInt()
            val startOffset = totalWritten - bytesToKeep

            var copied = 0
            while (copied < bytesToKeep) {
                val currentStreamPos = startOffset + copied
                val srcPos = (currentStreamPos % oldCapacity).toInt()
                val dstPos = (currentStreamPos % newCapacityBytes).toInt()

                val srcContiguous = oldCapacity - srcPos
                val dstContiguous = newCapacityBytes - dstPos
                val remainingToCopy = bytesToKeep - copied
                val chunkSize = minOf(remainingToCopy, srcContiguous, dstContiguous)

                System.arraycopy(oldData, srcPos, newData, dstPos, chunkSize)
                copied += chunkSize
            }

            data = newData
            _capacityBytes = newCapacityBytes
            baseStreamOffset = startOffset
            pruneExpiredSegmentsLocked()
        }
    }

    /**
     * Appends a new format segment starting at the current write head (issue #194).
     * Subsequent writes are stamped with [config] without resetting existing buffered audio.
     */
    fun setFormat(config: AudioConfig) {
        synchronized(lock) {
            if (segments.last().config != config) {
                segments.add(FormatSegment(startOffset = totalWritten, config = config))
                pruneExpiredSegmentsLocked()
            }
        }
    }

    /**
     * Returns the [AudioConfig] that was active when byte at [streamOffset] was written.
     */
    fun formatAt(streamOffset: Long): AudioConfig = synchronized(lock) {
        formatAtLocked(streamOffset)
    }

    private fun formatAtLocked(streamOffset: Long): AudioConfig {
        for (i in (segments.size - 1) downTo 0) {
            if (streamOffset >= segments[i].startOffset) {
                return segments[i].config
            }
        }
        return segments.first().config
    }

    /**
     * Returns all format segments covering the range `[startCursor, endCursor)` (issue #194).
     */
    fun activeSegments(startCursor: Long = oldestCursor(), endCursor: Long = writeCursor()): List<FormatSegment> =
        synchronized(lock) {
            if (endCursor <= startCursor) return emptyList()
            val result = mutableListOf<FormatSegment>()
            for (i in 0 until segments.size) {
                val seg = segments[i]
                val nextStart = if (i + 1 < segments.size) segments[i + 1].startOffset else endCursor
                if (nextStart > startCursor && seg.startOffset < endCursor) {
                    result.add(seg)
                }
            }
            result
        }

    /**
     * Computes the wall-clock audio duration (in milliseconds) spanning `[startOffset, endOffset)`,
     * resolving byte rates per segment across heterogeneous formats (issue #194).
     */
    fun durationMillis(startOffset: Long, endOffset: Long): Long = synchronized(lock) {
        durationMillisLocked(startOffset, endOffset)
    }

    private fun durationMillisLocked(startOffset: Long, endOffset: Long): Long {
        if (endOffset <= startOffset) return 0L
        var totalMillis = 0L
        for (i in 0 until segments.size) {
            val seg = segments[i]
            val nextStart = if (i + 1 < segments.size) segments[i + 1].startOffset else endOffset
            val rangeStart = maxOf(startOffset, seg.startOffset)
            val rangeEnd = minOf(endOffset, nextStart)
            if (rangeEnd > rangeStart) {
                val bytes = rangeEnd - rangeStart
                totalMillis += (bytes * MILLIS_PER_SECOND) / seg.config.bytesPerSecond
            }
        }
        return totalMillis
    }

    /**
     * Wall-clock audio duration currently held in the buffer (in milliseconds).
     */
    fun bufferedDurationMillis(): Long = synchronized(lock) {
        val oldest = oldestAvailableLocked()
        durationMillisLocked(oldest, totalWritten)
    }

    /**
     * Zeroes the backing array and resets the buffer to empty, without reallocating (the
     * no-allocation-after-construction guarantee still holds -- this is `Arrays.fill` plus
     * counter resets, no new arrays). Also resets the marker ring so no stale timing data
     * survives. After this call, [snapshot] returns an empty [AudioSnapshot] until the next
     * [write]. Intended for [AudioCaptureEngine.stop]: "stop means stop" is a product guarantee
     * here, not just hygiene -- raw mic PCM must not stay resident and queryable once capture
     * has ended.
     */
    fun clear() {
        synchronized(lock) {
            java.util.Arrays.fill(data, 0)
            java.util.Arrays.fill(markerOffsets, 0)
            java.util.Arrays.fill(markerTimestamps, 0)
            val lastConfig = segments.last().config
            segments.clear()
            segments.add(FormatSegment(startOffset = 0L, config = lastConfig))
            totalWritten = 0L
            baseStreamOffset = 0L
            markerCount = 0
            markerNextSlot = 0
        }
    }

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
            if (writeLen > _capacityBytes) {
                // This single write is bigger than the whole buffer; only its tail survives.
                srcPos += writeLen - _capacityBytes
                writeLen = _capacityBytes
            }

            val streamOffset = totalWritten
            val pos = (streamOffset % _capacityBytes).toInt()
            val firstPart = minOf(writeLen, _capacityBytes - pos)
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
            pruneExpiredSegmentsLocked()
        }
    }

    private fun pruneExpiredSegmentsLocked() {
        val oldestAvailable = oldestAvailableLocked()
        while (segments.size > 1 && segments[1].startOffset <= oldestAvailable) {
            segments.removeAt(0)
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
            val oldest = oldestAvailableLocked()
            val available = totalWritten - oldest
            if (available == 0L || durationMillis == 0L) return AudioSnapshot(ByteArray(0), clock())

            var remainingMillis = durationMillis
            var currentOffset = totalWritten

            for (i in (segments.size - 1) downTo 0) {
                val seg = segments[i]
                val segStart = maxOf(oldest, seg.startOffset)
                if (segStart >= currentOffset) continue
                val segBytes = currentOffset - segStart
                val segDurationMillis = (segBytes * MILLIS_PER_SECOND) / seg.config.bytesPerSecond
                if (remainingMillis <= segDurationMillis) {
                    val neededBytes = (remainingMillis * seg.config.bytesPerSecond) / MILLIS_PER_SECOND
                    currentOffset -= neededBytes
                    remainingMillis = 0L
                    break
                } else {
                    remainingMillis -= segDurationMillis
                    currentOffset = segStart
                }
            }

            val startOffset = maxOf(oldest, currentOffset)
            val length = (totalWritten - startOffset).toInt().coerceAtLeast(0)

            if (length == 0) return AudioSnapshot(ByteArray(0), estimateTimestampLocked(startOffset))

            val result = ByteArray(length)
            val startPos = (startOffset % _capacityBytes).toInt()
            val firstPart = minOf(length, _capacityBytes - startPos)
            System.arraycopy(data, startPos, result, 0, firstPart)
            val secondPart = length - firstPart
            if (secondPart > 0) {
                System.arraycopy(data, 0, result, firstPart, secondPart)
            }

            return AudioSnapshot(result, estimateTimestampLocked(startOffset))
        }
    }

    /**
     * Stream offset of the write head: the cursor a caller should start from to drain everything
     * written *from now on*, ignoring what is already buffered.
     */
    fun writeCursor(): Long = synchronized(lock) { totalWritten }

    /**
     * Stream offset of the oldest byte still buffered: the cursor a caller should start from to
     * drain the retained past first and then continue live (issue #47's "record forward,
     * including the last N minutes"). Equal to [writeCursor] on an empty buffer.
     */
    fun oldestCursor(): Long =
        synchronized(lock) { oldestAvailableLocked() }

    /**
     * Incremental drain read (issue #51): returns the PCM written since [cursor], up to
     * [maxBytes], together with the cursor to pass to the next call. Repeated calls threading the
     * returned `nextCursor` back in produce a gap-free, in-order byte stream.
     *
     * In the presence of multi-format segments (issue #194), returned chunks are bounded to segment
     * boundaries so that each chunk contains audio from exactly one [AudioConfig].
     */
    fun readSince(cursor: Long, maxBytes: Int): ReadSinceResult {
        require(cursor >= 0) { "cursor must not be negative, was $cursor" }
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
        synchronized(lock) {
            if (cursor > totalWritten) return ReadSinceResult.StreamReset(cursor, totalWritten)

            val oldestAvailable = oldestAvailableLocked()
            if (cursor < oldestAvailable) {
                return ReadSinceResult.Lapped(
                    requestedCursor = cursor,
                    oldestAvailableCursor = oldestAvailable,
                    lostBytes = oldestAvailable - cursor,
                )
            }

            // Find segment boundary ahead of cursor to avoid multi-format chunks
            var maxInSegment = (totalWritten - cursor).toInt()
            for (i in 0 until segments.size) {
                if (cursor < segments[i].startOffset) {
                    maxInSegment = minOf(maxInSegment, (segments[i].startOffset - cursor).toInt())
                    break
                }
            }

            val length = minOf(totalWritten - cursor, maxBytes.toLong(), maxInSegment.toLong()).toInt()
            if (length == 0) return ReadSinceResult.Data(EMPTY, cursor, cursor, 0L)

            // Allocation + copy are both under the lock, bounded by `length` (<= maxBytes)
            val result = ByteArray(length)
            val startPos = (cursor % _capacityBytes).toInt()
            val firstPart = minOf(length, _capacityBytes - startPos)
            System.arraycopy(data, startPos, result, 0, firstPart)
            val secondPart = length - firstPart
            if (secondPart > 0) {
                System.arraycopy(data, 0, result, firstPart, secondPart)
            }

            val nextCursor = cursor + length
            return ReadSinceResult.Data(
                bytes = result,
                startCursor = cursor,
                nextCursor = nextCursor,
                remainingBytes = totalWritten - nextCursor,
            )
        }
    }

    /**
     * Public wall-clock estimate for an arbitrary [streamOffset] (issue #72's bounded export
     * path needs this to compute a gap-fill window's wall-clock start without paying for a full
     * [snapshot] just to read [AudioSnapshot.startTimestampMillis]). Same marker interpolation
     * [snapshot] already uses internally, resolving heterogeneous byte rates across segments.
     */
    fun estimateTimestamp(streamOffset: Long): Long {
        require(streamOffset >= 0) { "streamOffset must not be negative, was $streamOffset" }
        return synchronized(lock) { estimateTimestampLocked(streamOffset) }
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
        return if (streamOffset >= markerOffset) {
            markerTimestamp + durationMillisLocked(markerOffset, streamOffset)
        } else {
            markerTimestamp - durationMillisLocked(streamOffset, markerOffset)
        }
    }

    private companion object {
        const val MARKER_CAPACITY = 8192
        const val MILLIS_PER_SECOND = 1000L

        /** Shared empty payload so an idle [readSince] poll allocates nothing at all. */
        val EMPTY = ByteArray(0)
    }
}
