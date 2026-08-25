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
 *
 * [readSince] (issue #51) takes the same single lock and adds no new one, so the deadlock-free
 * -by-construction argument above is unchanged. It differs from [snapshot] in what it holds the
 * lock for: a copy of only the bytes written since the caller's cursor (capped by its `maxBytes`),
 * not up to [capacityBytes]. A drain thread polling every 100-250 ms therefore holds the lock for
 * one poll interval of audio regardless of how large the retention window is -- the cost does not
 * scale with capacity the way [snapshot]'s does, which is why the table below does not apply to
 * it. What is *not* yet measured is the three-way case (continuous writes + continuous drains +
 * an occasional full [snapshot] contending at once); that measurement is issue #22's follow-up and
 * gates the live-writer consumer, not this primitive.
 *
 * One caveat, called out explicitly rather than assumed away: [snapshot] holds this lock across
 * its full body, including the destination array's allocation and a `System.arraycopy` of up to
 * [capacityBytes], which blocks the writer for that duration. **Measured** (issue #22 -- see
 * `RingBufferSnapshotLockBenchmarkTest` / `AudioRecordHeadroomInstrumentedTest`, PR #69), across
 * every retention window the UI actually offers, not just the extremes -- restructured below so
 * the configs that work lead, and the one that does not is impossible to miss:
 *
 * | Config | Works today? | snapshot() worst case observed (x86, 6+ runs) | `AudioRecord` headroom |
 * |---|---|---|---|
 * | 16 kHz/mono/5 min | Yes | 6.4 -- 7.3 ms | 90 ms (CI emulator) / **120 ms (Samsung S25)** |
 * | 16 kHz/mono/15 min | Yes | 14.8 -- 18.6 ms | 90 ms / **120 ms** |
 * | 16 kHz/mono/30 min (today's practical ceiling) | Yes | 30.6 -- 35.5 ms | 90 ms / **120 ms** |
 * | 16 kHz/mono/60 min (no longer offered by the UI -- issue #72's interim clamp lowered the max to 45 min after this table was written) | **No -- OOMs on real device, issue #72** | 16.1 -- 72.8 ms | 90 ms / **120 ms** |
 * | 44.1 kHz/stereo/60 min | **Hypothetical -- no UI path sets this** | 66.6 -- 242.9 ms | 91 ms / **121 ms** |
 *
 * `AudioRecord` headroom depends only on sample rate/channel count, not on retention window, so
 * the same 90/120 ms figure applies to every 16 kHz/mono row -- 5/15/30 min all sit comfortably
 * under it (worst case never exceeds 35.5 ms against a 90+ ms floor). The 60-minute row is the
 * stress case, and it is also the one row that **does not actually run on the target device**:
 * `@techlead` confirmed on the repo owner's real Samsung S25 (SM-S931B, arm64-v8a, Android 16)
 * that allocating this class's ~115.2 MB backing array plus [snapshot]'s ~115.2 MB destination
 * array together exceeds the app's 256 MB Dalvik heap growth limit (no `android:largeHeap`) --
 * `OutOfMemoryError` before any lock timing is reachable. Tracked as **issue #72**, deferred
 * pending a product-design rework; not fixed here. **The ARM copy time at 60 min was therefore
 * never measured on real hardware at all -- every 60-minute number in this doc is x86-only, and
 * that gap should not be read as "measured and fine".**
 *
 * All worst-case figures above are *ranges*, not single numbers, because they are not a stable
 * platform characteristic: one CI run (GitHub Actions `ubuntu-latest`, x86_64) measured
 * 16.1 ms / 66.6 ms for the 60-minute rows, but repeated local runs on two other x86_64 machines
 * measured up to 72.8 ms and 242.9 ms for the same two configs -- a ~4.5x and ~3.6x swing. The
 * likely cause (`@rev`'s read, consistent with what is being measured): every iteration allocates
 * a fresh 9 MB-635 MB destination array, so "worst of 30" mostly measures how unlucky that run's
 * GC pauses were, not a fixed copy throughput. **Treat "worst of 30" as a GC-sensitivity
 * indicator, not a hard ceiling** -- medians (observed single-digit-to-low-20s ms for the
 * 5/15/30/60-minute 16 kHz configs, ~95-115 ms for the hypothetical stereo config) are more
 * stable, but even they will move with heap pressure on a given device.
 *
 * Headroom was measured twice: the CI instrumented tier (API 30 `google_apis` x86_64 emulator)
 * gives 90 ms / 91 ms; the real Samsung S25 gives *better* numbers, 120 ms / 121 ms -- the real
 * device has more headroom than the emulator, not less. Both use the real `minBufferSize * 3`
 * [AudioCaptureEngine] allocates.
 *
 * **Verdict for what the app can actually run today (16 kHz/mono, 5/15/30 min): no redesign
 * needed.** Every observed worst case (up to 35.5 ms at 30 min, the practical ceiling) stays
 * comfortably under both the emulator headroom (90 ms, ~2.5x margin on the noisiest run) and the
 * real device headroom (120 ms, ~3.4x margin). The 60-minute option the UI still offers cannot be
 * given a verdict on real hardware at all -- it fails before the lock is even reached (issue #72).
 *
 * **The hypothetical 44.1 kHz/stereo/60 min config leans unsafe, not merely thin-margin**: its
 * observed worst case (66.6-242.9 ms) exceeds *both* headroom figures (91 ms / 121 ms) in most
 * runs, not just at the extreme. Not reachable through the UI today, so no locking change is
 * warranted for the current app -- but if a future change (issue #47, or a sample-rate setting)
 * ever exposes 44.1 kHz/stereo, `snapshot`'s current lock-the-whole-copy design must be redesigned
 * *before* that ships, not merely re-evaluated (see follow-up issue #71).
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
            totalWritten = 0L
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
        synchronized(lock) { totalWritten - minOf(totalWritten, capacityBytes.toLong()) }

    /**
     * Incremental drain read (issue #51): returns the PCM written since [cursor], up to
     * [maxBytes], together with the cursor to pass to the next call. Repeated calls threading the
     * returned `nextCursor` back in produce a gap-free, in-order byte stream.
     *
     * This is deliberately **not** [snapshot] under another name. [snapshot] answers "give me the
     * last N minutes" and copies up to [capacityBytes]; this answers "give me what I have not seen
     * yet" and copies `min(totalWritten - cursor, maxBytes)` -- for a drain polling every
     * 100-250 ms that is one poll interval of audio (a few KB), whatever the retention window is.
     * Two consequences that are the whole point of the primitive: it never allocates a
     * second full-size copy of the window (issue #72), and **the lock hold is proportional to the
     * bytes actually copied, not to capacity** -- a caller that keeps up bounds the writer's worst
     * case at bytes-per-poll, independently of whether retention is 5 minutes or 45.
     *
     * [maxBytes] is deliberately **not** defaulted (PR #86 review, `@sec` / `@rev`). An earlier
     * revision defaulted it to [capacityBytes] and asked callers in this doc to override it when
     * seeding from [oldestCursor] -- which meant the one call shape the API anticipates,
     * `readSince(oldestCursor())`, took the capacity-sized allocate-and-copy-under-the-lock path
     * by omission: exactly the [snapshot]-shaped cost this primitive exists to avoid, and the
     * shape behind issue #72's OOM. A comment is not a substitute for a signature that cannot be
     * misused by accident -- the same principle [ReadSinceResult] follows -- so every caller now
     * states its own bound. A drain on a 100-250 ms cadence wants about one poll interval of
     * audio, a few KB; a caller catching up from [oldestCursor] loops with the same small bound
     * rather than asking for the window in one call.
     *
     * Falling behind is reported, never smoothed over: if the ring wrapped past [cursor] the
     * result is [ReadSinceResult.Lapped] with the exact byte count that was lost, and if the
     * stream was reset under the caller ([clear]) it is [ReadSinceResult.StreamReset]. Neither is
     * expressible as a shorter [ReadSinceResult.Data], so a short read always means one thing
     * only: that is all that had been written yet.
     *
     * A negative [cursor] or a non-positive [maxBytes] is a programming error, not a runtime
     * condition -- no legal sequence of calls produces one -- so those throw.
     */
    fun readSince(cursor: Long, maxBytes: Int): ReadSinceResult {
        require(cursor >= 0) { "cursor must not be negative, was $cursor" }
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
        synchronized(lock) {
            if (cursor > totalWritten) return ReadSinceResult.StreamReset(cursor, totalWritten)

            val oldestAvailable = totalWritten - minOf(totalWritten, capacityBytes.toLong())
            if (cursor < oldestAvailable) {
                return ReadSinceResult.Lapped(
                    requestedCursor = cursor,
                    oldestAvailableCursor = oldestAvailable,
                    lostBytes = oldestAvailable - cursor,
                )
            }

            val length = minOf(totalWritten - cursor, maxBytes.toLong()).toInt()
            if (length == 0) return ReadSinceResult.Data(EMPTY, cursor, cursor, 0L)

            // Allocation + copy are both under the lock, as in snapshot(), but bounded by
            // `length` (<= maxBytes) rather than by capacityBytes -- see the doc above.
            val result = ByteArray(length)
            val startPos = (cursor % capacityBytes).toInt()
            val firstPart = minOf(length, capacityBytes - startPos)
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
     * [snapshot] already uses internally, just reachable for a cursor a caller obtained from
     * [writeCursor]/[oldestCursor] instead of only for whatever [snapshot] itself just copied.
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
        val deltaBytes = streamOffset - markerOffset
        val deltaMillis = (deltaBytes * MILLIS_PER_SECOND) / bytesPerSecond
        return markerTimestamp + deltaMillis
    }

    private companion object {
        const val MARKER_CAPACITY = 8192
        const val MILLIS_PER_SECOND = 1000L

        /** Shared empty payload so an idle [readSince] poll allocates nothing at all. */
        val EMPTY = ByteArray(0)
    }
}
