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
 *
 * One caveat, called out explicitly rather than assumed away: [snapshot] holds this lock across
 * its full body, including the destination array's allocation and a `System.arraycopy` of up to
 * [capacityBytes], which blocks the writer for that duration. **Measured** (issue #22 -- see
 * `RingBufferSnapshotLockBenchmarkTest` / `AudioRecordHeadroomInstrumentedTest`, PR #69) --
 * revised below after independent reproductions surfaced real variance the first pass understated
 * (`@rev`/`@sec`/`@techlead` findings, same PR thread):
 *
 * | Config | snapshot() worst case observed (5 x86 runs) | `AudioRecord` headroom |
 * |---|---|---|
 * | 16 kHz/mono/60 min (real: max retention the UI offers) | 16.1 -- 72.8 ms | 90 ms (CI emulator) / **120 ms (Samsung S25, real device)** |
 * | 44.1 kHz/stereo/60 min (**hypothetical** -- no UI path sets this today) | 66.6 -- 217.8 ms | 91 ms (CI emulator) / **121 ms (Samsung S25, real device)** |
 *
 * The worst-case column is a *range*, not a single number, because it is not a stable platform
 * characteristic: one CI run (GitHub Actions `ubuntu-latest`, x86_64) measured 16.1 ms / 66.6 ms,
 * but three local runs on a second x86_64 machine measured 47.5/57.2/72.8 ms and
 * 124.5/217.8/127.1 ms for the same two configs, a third x86 machine measured 114.9 ms for the
 * stereo config, and a fourth run (this repo's own dev workstation) measured 49.7 ms / 142.1 ms.
 * The likely cause (`@rev`'s read, and consistent with what is being measured): every iteration
 * allocates a fresh 115 MB-635 MB destination array, so "worst of N" mostly measures how unlucky
 * that run's GC pauses were, not a fixed copy throughput. **Treat "worst of 30" as a
 * GC-sensitivity indicator, not a hard ceiling** -- the median (11-24 ms observed for the 16 kHz
 * config across the two runs that recorded one) is the more stable number, but even it will move
 * with heap pressure on a given device.
 *
 * Headroom was measured twice: the CI instrumented tier (API 30 `google_apis` x86_64 emulator)
 * gives 90 ms / 91 ms; the repo owner's actual Samsung S25 (SM-S931B, arm64-v8a, Android 16)
 * gives better real numbers, 120 ms / 121 ms -- the real device has *more* headroom than the
 * emulator, not less. Both use the real `minBufferSize * 3` [AudioCaptureEngine] allocates.
 *
 * **Verdict for the real config (16 kHz/mono, up to 60 min -- the only shape the UI can produce
 * today): every observed worst case (16.1-72.8 ms) still stays under both the emulator headroom
 * (90 ms) and the real device headroom (120 ms).** No redesign needed for this app as it ships.
 * The margin is real but far less comfortable than a single CI run suggested: as little as ~1.24x
 * against the emulator headroom on the noisiest x86 run, not the original single-run "~5.6x,
 * comfortable" framing -- that framing is retracted; a range this GC-sensitive cannot be
 * summarized as one comfortable multiplier.
 *
 * **On the real S25, though, this config may not run at all at 60 min: allocating it hits
 * `OutOfMemoryError` before any lock timing is reachable.** `@techlead` confirmed this by running
 * `AudioRecordHeadroomInstrumentedTest`-style allocation directly on-device: the app's Dalvik heap
 * growth limit is 256 MB with no `android:largeHeap`, and this class's backing array
 * (~115.2 MB at 60 min) plus [snapshot]'s destination array (another ~115.2 MB) together exceed
 * that limit. Tracked as **issue #72** -- a real bug independent of lock hold time, since 60 min
 * is a selectable retention option today. The ARM copy time for this config was therefore never
 * measured on real hardware at all; do not read the gap as "measured and fine".
 *
 * **The hypothetical 44.1 kHz/stereo/60 min config is worse than the first pass reported: its
 * observed worst case (66.6-217.8 ms) exceeds *both* headroom figures (91 ms / 121 ms) in the
 * majority of runs**, not just in a thin-margin edge case. Only the original CI run (66.6 ms) and
 * one other machine (114.9 ms, still under the real device's 121 ms) stayed under; three
 * independent x86 runs did not. This config is not reachable through the UI today, so no locking
 * change is warranted for the current app -- but if a future change (issue #47, or a sample-rate
 * setting) ever exposes 44.1 kHz/stereo, `snapshot`'s current lock-the-whole-copy design must be
 * redesigned *before* that ships, not merely re-evaluated -- the data already leans "unsafe", not
 * "thin but maybe fine" (see follow-up issue #71, updated with this data).
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
