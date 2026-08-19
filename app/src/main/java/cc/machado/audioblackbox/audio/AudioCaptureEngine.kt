package cc.machado.audioblackbox.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable lifecycle of [AudioCaptureEngine]. Consumed by onboarding/foreground-service
 * (Module 2) and the UI fill-state display (Module 4). */
sealed interface CaptureState {
    /** Never started, or cleanly stopped. No `AudioRecord` held. */
    data object Idle : CaptureState

    /** Actively reading microphone audio into the ring buffer. */
    data object Recording : CaptureState

    /** `AudioRecord` still open, but reads are not being written into the ring buffer (e.g. a
     * phone call in progress). Set/cleared via [AudioCaptureEngine.pause]/[AudioCaptureEngine.resume]. */
    data object Paused : CaptureState

    /** Capture cannot continue. [reason] disambiguates why; the engine always releases
     * `AudioRecord` before entering this state. */
    data class Error(val reason: CaptureErrorReason, val message: String) : CaptureState
}

/** Why [CaptureState.Error] happened, so callers can decide whether retrying makes sense. */
enum class CaptureErrorReason {
    /** `AudioRecord.getMinBufferSize` returned an error for this [AudioConfig]. */
    UNSUPPORTED_CONFIG,

    /** `AudioRecord` was constructed but never reached `STATE_INITIALIZED`. */
    AUDIO_RECORD_INIT_FAILED,

    /** `AudioRecord.read()` returned `ERROR_INVALID_OPERATION`. */
    READ_INVALID_OPERATION,

    /** `AudioRecord.read()` returned `ERROR_BAD_VALUE`. */
    READ_BAD_VALUE,

    /** `AudioRecord.read()` returned `ERROR_DEAD_OBJECT` (server process died). */
    READ_DEAD_OBJECT,

    /** `AudioRecord.read()` returned an error code not covered above. */
    READ_UNKNOWN_ERROR,

    /** Pre-allocating the ring buffer's backing array threw `OutOfMemoryError`. */
    BUFFER_ALLOCATION_FAILED,
}

/**
 * Opens `AudioRecord` on a dedicated thread and feeds a [RingBuffer] sized from [config].
 *
 * Callers must hold `RECORD_AUDIO` before calling [start]; this class does not request
 * permissions itself (see `cc.machado.audioblackbox.permissions`).
 *
 * [start]/[stop] are idempotent: calling either while already in the target state is a no-op.
 * The ring buffer is allocated lazily inside [start] (not at construction) specifically so an
 * `OutOfMemoryError` on a low-memory device surfaces as [CaptureState.Error] through [state]
 * rather than crashing construction. `AudioRecord` is always released before this class leaves
 * the Recording/Paused states, whether via [stop] or a read error, so there is no leak across
 * start/stop cycles.
 */
class AudioCaptureEngine(
    private val config: AudioConfig = AudioConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val audioRecordFactory: (AudioConfig, Int) -> AudioRecord = ::createAudioRecord,
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    // Guards state transitions and the fields below. Only `write()`-equivalent work (the
    // capture loop's read/write) happens outside this lock, on the dedicated capture thread.
    private val lock = Any()

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    // Written only inside `synchronized(lock)` (start()/captureLoop's finally), but read from
    // arbitrary caller threads by snapshot() without taking `lock` (export/UI call this from
    // their own threads, and routing every snapshot() through `lock` would serialize them behind
    // whatever the capture thread is doing). @Volatile is sufficient -- not a full lock -- because
    // the only cross-thread requirement here is that a write to this reference by the capture
    // thread become visible to a reader thread; RingBuffer itself is independently thread-safe
    // (its own intrinsic lock guards `write`/`snapshot`), so there is no multi-field invariant
    // between `ringBuffer` and other fields that a reader needs a consistent view of.
    @Volatile private var ringBuffer: RingBuffer? = null

    @Volatile private var stopRequested = false
    @Volatile private var paused = false

    // Incremented under `lock` every time start() installs a new session. The capture thread's
    // own cleanup (captureLoop's `finally`) captures the generation it was started with and only
    // mutates `audioRecord`/`captureThread`/`ringBuffer`/`state` if that generation is still
    // current -- otherwise it means a newer start() has already superseded this thread, and
    // touching those fields would clobber the newer session (see PR #20 review, finding 2).
    private var generation = 0L

    // Set (under `lock`) by stop() to the thread it is about to join, and cleared by that
    // thread's own cleanup once done. @Volatile so start() can wait on it *without* holding
    // `lock` (joining while holding `lock` would deadlock against the capture thread's own
    // `synchronized(lock)` use in its `finally`). This closes the window where a start() arriving
    // between stop() releasing `lock` and the capture thread's `finally` completing would
    // otherwise see stale "still Recording" state and silently no-op instead of starting a new
    // session (see PR #20 review, finding 4).
    @Volatile private var teardownThread: Thread? = null

    /** The buffer the capture thread is writing into, or `null` before the first [start] or
     * after [stop] (see [RingBuffer.clear] -- capture is not considered stopped until the raw
     * PCM is unreachable through this method). */
    fun snapshot(durationMillis: Long): AudioSnapshot? = ringBuffer?.snapshot(durationMillis)

    /**
     * Starts a capture session: allocates the ring buffer, opens `AudioRecord`, and launches the
     * dedicated read/write thread. No-op if already [CaptureState.Recording] or
     * [CaptureState.Paused]. Any failure (buffer OOM, `AudioRecord` init failure) is surfaced via
     * [state] as [CaptureState.Error] instead of throwing.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        // Wait out any teardown still in flight from a previous stop()/read-error before
        // deciding whether to start a new session -- otherwise this call could race a stale
        // cleanup (finding 2) or be silently dropped because it still observes the old
        // Recording/Paused state (finding 4). Deliberately outside `lock`: see `teardownThread`.
        teardownThread?.join()

        synchronized(lock) {
            when (_state.value) {
                is CaptureState.Recording, is CaptureState.Paused -> return
                else -> Unit
            }

            val capacityBytes = config.totalBufferBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val buffer = try {
                RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = config.bytesPerSecond, clock = clock)
            } catch (oom: OutOfMemoryError) {
                _state.value = CaptureState.Error(
                    CaptureErrorReason.BUFFER_ALLOCATION_FAILED,
                    "Failed to allocate $capacityBytes-byte ring buffer: ${oom.message}",
                )
                return
            }

            val channelConfig = channelConfigFor(config.channelCount)
            val minBufferSize = AudioRecord.getMinBufferSize(
                config.sampleRateHz,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                _state.value = CaptureState.Error(
                    CaptureErrorReason.UNSUPPORTED_CONFIG,
                    "AudioRecord.getMinBufferSize returned $minBufferSize for $config",
                )
                return
            }

            val record = try {
                audioRecordFactory(config, minBufferSize)
            } catch (e: Exception) {
                _state.value = CaptureState.Error(
                    CaptureErrorReason.AUDIO_RECORD_INIT_FAILED,
                    "AudioRecord construction threw: ${e.message}",
                )
                return
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _state.value = CaptureState.Error(
                    CaptureErrorReason.AUDIO_RECORD_INIT_FAILED,
                    "AudioRecord.state = ${record.state}, expected STATE_INITIALIZED",
                )
                return
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                _state.value = CaptureState.Error(
                    CaptureErrorReason.AUDIO_RECORD_INIT_FAILED,
                    "AudioRecord.startRecording() did not reach RECORDSTATE_RECORDING",
                )
                return
            }

            ringBuffer = buffer
            audioRecord = record
            stopRequested = false
            paused = false
            generation += 1
            val myGeneration = generation
            _state.value = CaptureState.Recording

            val thread =
                Thread({ captureLoop(record, buffer, minBufferSize, myGeneration) }, "AudioCaptureEngine")
            thread.isDaemon = true
            captureThread = thread
            thread.start()
        }
    }

    /** Stops capture and releases `AudioRecord`. No-op if already [CaptureState.Idle]. Blocks
     * until the capture thread has fully exited (including the [CaptureState.Error] case, where
     * the thread may still be mid-cleanup), so it is safe to assume no leak immediately after
     * this returns. */
    fun stop() {
        var threadToJoin: Thread? = null
        var wasError = false
        var myGeneration = -1L
        synchronized(lock) {
            when (_state.value) {
                is CaptureState.Idle -> return
                is CaptureState.Error -> {
                    // Do NOT write Idle here. The capture thread already exited its read loop
                    // (that's why state is Error) but has not released `AudioRecord`/cleared the
                    // ring buffer yet -- that happens later, in its own `finally`, guarded by
                    // `lock`. Writing Idle before that cleanup runs would let a third thread
                    // observe state == Idle while snapshot() still returns real, un-cleared PCM,
                    // violating Idle's documented "no AudioRecord held" contract (see PR #20
                    // review, second-round finding). We instead join first and write Idle
                    // afterward, once cleanup is guaranteed complete -- see below.
                    wasError = true
                    myGeneration = generation
                    threadToJoin = captureThread
                }
                else -> {
                    stopRequested = true
                    threadToJoin = captureThread
                }
            }
            teardownThread = threadToJoin
        }
        // Joined outside the lock: the capture thread's cleanup below acquires `lock` itself,
        // and joining while holding it would deadlock.
        threadToJoin?.join()
        if (wasError) {
            synchronized(lock) {
                // Only stamp Idle if no newer start() has superseded this session in the
                // meantime (same guard the capture thread's own cleanup uses -- see PR #20
                // review, finding 2). The capture thread's `finally` deliberately leaves Error
                // in place instead of overwriting it with Idle, so this is the only place that
                // ever performs this transition, and only after `threadToJoin?.join()` above has
                // proven cleanup (release + ring buffer clear) already ran.
                if (generation == myGeneration && _state.value is CaptureState.Error) {
                    _state.value = CaptureState.Idle
                }
            }
        }
    }

    /** Suspends writes into the ring buffer without closing `AudioRecord` (Module 2: phone
     * call interruption). No-op unless currently [CaptureState.Recording]. */
    fun pause() {
        synchronized(lock) {
            if (_state.value is CaptureState.Recording) {
                paused = true
                _state.value = CaptureState.Paused
            }
        }
    }

    /** Resumes writes into the ring buffer. No-op unless currently [CaptureState.Paused]. */
    fun resume() {
        synchronized(lock) {
            if (_state.value is CaptureState.Paused) {
                paused = false
                _state.value = CaptureState.Recording
            }
        }
    }

    private fun captureLoop(record: AudioRecord, buffer: RingBuffer, minBufferSize: Int, myGeneration: Long) {
        // Scratch array allocated once, outside the loop, and reused for every read().
        val scratch = ByteArray(minBufferSize)
        try {
            while (!stopRequested) {
                val bytesRead = record.read(scratch, 0, scratch.size)
                if (bytesRead > 0) {
                    if (!paused) buffer.write(scratch, 0, bytesRead)
                } else if (bytesRead < 0) {
                    val reason = mapReadError(bytesRead)
                    synchronized(lock) {
                        _state.value = CaptureState.Error(
                            reason,
                            "AudioRecord.read() returned $bytesRead",
                        )
                    }
                    return
                }
                // bytesRead == 0: nothing available yet, loop again.
            }
        } finally {
            // "Stop means stop": zero the raw PCM this session buffered, regardless of whether a
            // newer session has already superseded this thread -- it's this thread's own buffer,
            // never shared, so clearing it is always safe (see PR #20 review, finding 3).
            buffer.clear()
            synchronized(lock) {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                    // Already stopped/uninitialized; release() below still runs.
                }
                record.release()
                // Only touch the shared fields if this thread's session is still the current
                // one. If a newer start() already ran (generation moved on) while this thread was
                // between its read-error and this cleanup, those fields now belong to the newer
                // session -- nulling them here would orphan it (see PR #20 review, finding 2).
                if (generation == myGeneration) {
                    audioRecord = null
                    captureThread = null
                    ringBuffer = null
                    if (_state.value !is CaptureState.Error) {
                        _state.value = CaptureState.Idle
                    }
                }
                if (teardownThread === Thread.currentThread()) {
                    teardownThread = null
                }
            }
        }
    }

    private companion object {
        fun mapReadError(code: Int): CaptureErrorReason = when (code) {
            AudioRecord.ERROR_INVALID_OPERATION -> CaptureErrorReason.READ_INVALID_OPERATION
            AudioRecord.ERROR_BAD_VALUE -> CaptureErrorReason.READ_BAD_VALUE
            AudioRecord.ERROR_DEAD_OBJECT -> CaptureErrorReason.READ_DEAD_OBJECT
            else -> CaptureErrorReason.READ_UNKNOWN_ERROR
        }

        fun channelConfigFor(channelCount: Int): Int = when (channelCount) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            2 -> AudioFormat.CHANNEL_IN_STEREO
            else -> throw IllegalArgumentException(
                "Unsupported channelCount: $channelCount (only mono=1 or stereo=2 supported)",
            )
        }

        @SuppressLint("MissingPermission")
        fun createAudioRecord(config: AudioConfig, minBufferSize: Int): AudioRecord {
            val channelConfig = channelConfigFor(config.channelCount)
            // A couple of `minBufferSize` multiples of internal headroom so the driver has room
            // to land audio between our read() calls without dropping frames.
            val internalBufferSize = minBufferSize * 3
            return AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRateHz,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                internalBufferSize,
            )
        }
    }
}
