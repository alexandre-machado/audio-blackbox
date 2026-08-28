package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioCaptureEngine
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.ReadSinceResult
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable lifecycle of forward recording (issue #54). */
sealed interface ForwardRecordingState {
    data object Idle : ForwardRecordingState
    data class Recording(val displayName: String, val bytesWritten: Long) : ForwardRecordingState
    data class Success(val displayName: String, val bytesWritten: Long) : ForwardRecordingState
    data class Error(val reason: ForwardRecordingFailureReason, val message: String) : ForwardRecordingState
}

/** Why a forward recording session failed. */
enum class ForwardRecordingFailureReason {
    /** Audio capture is not running / buffer is not allocated. */
    CAPTURE_NOT_ACTIVE,

    /**
     * The forward drain thread fell behind the capture writer and the ring buffer wrapped past its
     * cursor ([ReadSinceResult.Lapped]). PCM bytes were lost, so the session is terminated visibly
     * (never a silent no-op / silent data loss, per issue #29).
     */
    CURSOR_LAPPED,

    /** The capture buffer was reset/cleared while a forward recording session was running. */
    STREAM_RESET,

    /** [StreamingExportSink.openStreaming] threw (e.g. MediaStore insert rejected, disk full). */
    SINK_OPEN_FAILED,

    /** Writing to the streaming writer or sink threw. */
    WRITE_FAILED,

    /** A forward recording session is already running on this engine. */
    FORWARD_RECORDING_ALREADY_IN_PROGRESS,

    /** The forward recording session was explicitly cancelled. */
    CANCELLED,

    /** An unexpected error occurred during encoding or draining. */
    UNEXPECTED_FAILURE,
}

/**
 * Orchestrates live forward recording from the rolling ring buffer into a streaming AAC encoder
 * and early-committed MediaStore sink (issue #54).
 *
 * Runs a dedicated writer/drain thread that continuously pulls newly captured PCM from
 * [RingBuffer.readSince] and feeds [StreamingAacWriter], while preserving the single-writer and
 * zero-disk-on-capture invariants of [AudioCaptureEngine].
 *
 * ## Invariants & Threading
 * - **Single Capture Writer**: The capture thread remains the sole writer to the ring buffer and never
 *   blocks on disk, IPC, or `MediaCodec`.
 * - **Dedicated Drain Thread**: Live drain and AAC encoding happen strictly off the capture thread on a
 *   dedicated worker thread.
 * - **Continuous Retention Window**: The ring buffer continues to roll during forward recording, allowing
 *   concurrent snapshots ("save the past") without frame drops.
 * - **Lapped Cursor Detection**: If the drain falls behind and the cursor is lapped ([ReadSinceResult.Lapped]),
 *   a visible [ForwardRecordingState.Error] is surfaced immediately; silent data loss is forbidden.
 * - **Live Gap Insertion**: System interruptions (pause/resume cycles) during forward recording are
 *   tracked and injected into the stream as wall-clock silence frames to ensure timeline continuity.
 */
class ForwardRecordingEngine(
    private val config: AudioConfig,
    private val readSinceProvider: (cursor: Long, maxBytes: Int) -> ReadSinceResult?,
    private val writeCursorProvider: () -> Long?,
    private val oldestCursorProvider: () -> Long? = { null },
    private val gapsProvider: () -> List<PauseGap>,
    private val sink: StreamingExportSink,
    private val writerFactory: (StreamingExportTarget, AudioConfig) -> StreamingAudioWriter = { target, cfg ->
        StreamingAacWriter(target, cfg)
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val drainChunkSizeBytes: Int = DEFAULT_DRAIN_CHUNK_SIZE_BYTES,
) {
    constructor(
        engine: AudioCaptureEngine,
        sink: StreamingExportSink,
        config: AudioConfig = AudioConfig(),
        writerFactory: (StreamingExportTarget, AudioConfig) -> StreamingAudioWriter = { target, cfg ->
            StreamingAacWriter(target, cfg)
        },
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        config = config,
        readSinceProvider = { cursor, maxBytes -> engine.readSince(cursor, maxBytes) },
        writeCursorProvider = { engine.writeCursor() },
        oldestCursorProvider = { engine.oldestCursor() },
        gapsProvider = { engine.gaps.value },
        sink = sink,
        writerFactory = writerFactory,
        clock = clock,
    )

    private val _state = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
    val state: StateFlow<ForwardRecordingState> = _state.asStateFlow()

    private val lock = Any()
    private var activeDrainThread: Thread? = null
    private var activeTarget: StreamingExportTarget? = null
    private var activeWriter: StreamingAudioWriter? = null
    private val stopRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private var wakeUpLatch = CountDownLatch(0)

    fun acknowledgeTerminalState() {
        synchronized(lock) {
            val current = _state.value
            if (current is ForwardRecordingState.Success || current is ForwardRecordingState.Error) {
                _state.value = ForwardRecordingState.Idle
            }
        }
    }

    /**
     * Starts a live forward recording session. Always drains the retained past first (issue
     * #139 -- the repo owner's 2026-08-26 decision that forward recording has exactly one mode:
     * it always includes whatever the ring buffer already retains before continuing live). There
     * used to be a `startFromOldest` parameter here that let a caller opt out into a forward-only
     * session; every call site converged on `true`, so the flag was deleted rather than pinned --
     * a knob with one legal value is exactly the seam that let this regress silently.
     *
     * @param customDisplayName Optional explicit filename.
     */
    fun start(customDisplayName: String? = null): ForwardRecordingState {
        synchronized(lock) {
            if (_state.value is ForwardRecordingState.Recording) {
                return ForwardRecordingState.Error(
                    ForwardRecordingFailureReason.FORWARD_RECORDING_ALREADY_IN_PROGRESS,
                    "Forward recording is already in progress",
                )
            }

            val startCursor = oldestCursorProvider() ?: writeCursorProvider()

            if (startCursor == null) {
                val err = ForwardRecordingState.Error(
                    ForwardRecordingFailureReason.CAPTURE_NOT_ACTIVE,
                    "Audio capture is not running",
                )
                _state.value = err
                return err
            }

            val displayName = customDisplayName ?: generateDisplayName(Date(clock()))
            val target = try {
                sink.openStreaming(displayName, StreamingAacWriter.MIME_TYPE_M4A)
            } catch (e: Exception) {
                val err = ForwardRecordingState.Error(
                    ForwardRecordingFailureReason.SINK_OPEN_FAILED,
                    "Failed to open streaming sink: ${e.message}",
                )
                _state.value = err
                return err
            }

            val writer = try {
                writerFactory(target, config)
            } catch (e: Exception) {
                target.close()
                val err = ForwardRecordingState.Error(
                    ForwardRecordingFailureReason.SINK_OPEN_FAILED,
                    "Failed to create streaming writer: ${e.message}",
                )
                _state.value = err
                return err
            }

            stopRequested.set(false)
            cancelRequested.set(false)
            wakeUpLatch = CountDownLatch(1)
            activeTarget = target
            activeWriter = writer

            val sessionStartMillis = clock()
            val initialGaps = gapsProvider()

            _state.value = ForwardRecordingState.Recording(displayName, 0L)

            val drainThread = Thread({
                drainLoop(displayName, startCursor, target, writer, sessionStartMillis, initialGaps)
            }, "ForwardRecordingDrain")
            drainThread.isDaemon = true
            activeDrainThread = drainThread
            drainThread.start()

            return _state.value
        }
    }

    /**
     * Stops the active forward recording session cleanly, flushing and finalizing the .m4a file.
     * Blocks until the drain thread finishes finalization.
     */
    fun stop(): ForwardRecordingState {
        var threadToJoin: Thread? = null
        synchronized(lock) {
            if (_state.value !is ForwardRecordingState.Recording) {
                return _state.value
            }
            stopRequested.set(true)
            wakeUpLatch.countDown()
            threadToJoin = activeDrainThread
        }
        threadToJoin?.join()
        return _state.value
    }

    /**
     * Cancels the active forward recording session, releasing resources without finalizing.
     */
    fun cancel(): ForwardRecordingState {
        var threadToJoin: Thread? = null
        synchronized(lock) {
            if (_state.value !is ForwardRecordingState.Recording) {
                return _state.value
            }
            cancelRequested.set(true)
            stopRequested.set(true)
            wakeUpLatch.countDown()
            threadToJoin = activeDrainThread
        }
        threadToJoin?.join()
        return _state.value
    }

    private fun drainLoop(
        displayName: String,
        initialCursor: Long,
        target: StreamingExportTarget,
        writer: StreamingAudioWriter,
        sessionStartMillis: Long,
        initialGaps: List<PauseGap>,
    ) {
        var cursor = initialCursor
        var totalBytesDrained = 0L
        val handledGaps = initialGaps.toMutableSet()
        var lastRefinalizeAtNanos = System.nanoTime()

        // Re-finalizes the MediaStore row's SIZE/DURATION from what has actually been written so
        // far (issue #140/#53 item 3). Never lets a refinalize failure interrupt the recording (see
        // StreamingExportTarget.refinalizeMetadata's doc); target.refinalizeMetadata is itself
        // already best-effort/non-throwing, this is defense in depth.
        fun refinalize() {
            try {
                target.refinalizeMetadata()
            } catch (_: Throwable) {}
        }

        try {
            while (!stopRequested.get() && !cancelRequested.get()) {
                // Check and inject any completed pause gaps that occurred since session start
                val currentGaps = gapsProvider()
                for (gap in currentGaps) {
                    if (gap.endTimestampMillis >= sessionStartMillis && handledGaps.add(gap)) {
                        writer.writeGap(gap)
                    }
                }

                when (val result = readSinceProvider(cursor, drainChunkSizeBytes)) {
                    null -> {
                        // Capture engine stopped unexpectedly
                        synchronized(lock) {
                            _state.value = ForwardRecordingState.Error(
                                ForwardRecordingFailureReason.CAPTURE_NOT_ACTIVE,
                                "Capture stopped unexpectedly during forward recording",
                            )
                        }
                        return
                    }
                    is ReadSinceResult.Lapped -> {
                        synchronized(lock) {
                            _state.value = ForwardRecordingState.Error(
                                ForwardRecordingFailureReason.CURSOR_LAPPED,
                                "Forward writer fell behind: ${result.lostBytes} bytes lost",
                            )
                        }
                        return
                    }
                    is ReadSinceResult.StreamReset -> {
                        synchronized(lock) {
                            _state.value = ForwardRecordingState.Error(
                                ForwardRecordingFailureReason.STREAM_RESET,
                                "Capture stream was reset under cursor $cursor",
                            )
                        }
                        return
                    }
                    is ReadSinceResult.Data -> {
                        if (result.bytes.isNotEmpty()) {
                            writer.write(result.bytes)
                            cursor = result.nextCursor
                            totalBytesDrained += result.bytes.size
                            synchronized(lock) {
                                if (_state.value is ForwardRecordingState.Recording) {
                                    _state.value = ForwardRecordingState.Recording(displayName, totalBytesDrained)
                                }
                            }
                        }

                        // Periodic re-finalization (#47 item 3 / issue #140): a clean stop() alone
                        // does not protect a long recording against process death mid-session --
                        // the whole point of a "black box" is surviving exactly that kind of
                        // unexpected termination, and stop-time-only re-finalization would leave the
                        // row permanently claiming near-zero size/duration in that case, the same
                        // "leaked wrong state" class of bug #47 already ruled out for IS_PENDING.
                        // Throttled off wall-clock time (not iteration count, since chunk size is
                        // data-dependent) so it never turns into a MediaStore update per drain
                        // iteration.
                        val nowNanos = System.nanoTime()
                        if (nowNanos - lastRefinalizeAtNanos >= REFINALIZE_INTERVAL_NANOS) {
                            refinalize()
                            lastRefinalizeAtNanos = nowNanos
                        }

                        if (result.remainingBytes == 0L && !stopRequested.get()) {
                            wakeUpLatch.await(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                        }
                    }
                }
            }

            if (cancelRequested.get()) {
                synchronized(lock) {
                    _state.value = ForwardRecordingState.Error(
                        ForwardRecordingFailureReason.CANCELLED,
                        "Forward recording cancelled",
                    )
                }
                return
            }

            // Clean stop: drain any remaining audio up to the write head
            var finalDrainDone = false
            val deadline = System.nanoTime() + FINAL_DRAIN_TIMEOUT_NANOS
            while (!finalDrainDone && System.nanoTime() < deadline) {
                // Inject any final gaps
                val currentGaps = gapsProvider()
                for (gap in currentGaps) {
                    if (gap.endTimestampMillis >= sessionStartMillis && handledGaps.add(gap)) {
                        writer.writeGap(gap)
                    }
                }

                when (val result = readSinceProvider(cursor, drainChunkSizeBytes)) {
                    is ReadSinceResult.Data -> {
                        if (result.bytes.isNotEmpty()) {
                            writer.write(result.bytes)
                            cursor = result.nextCursor
                            totalBytesDrained += result.bytes.size
                        }
                        if (result.remainingBytes == 0L) {
                            finalDrainDone = true
                        }
                    }
                    else -> finalDrainDone = true
                }
            }

            writer.finish()
            target.finish()

            synchronized(lock) {
                _state.value = ForwardRecordingState.Success(displayName, totalBytesDrained)
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                _state.value = ForwardRecordingState.Error(
                    ForwardRecordingFailureReason.WRITE_FAILED,
                    "Forward recording write failed: ${t.message}",
                )
            }
        } finally {
            // Authoritative final settle (issue #140): runs on every exit path -- clean stop,
            // cancel, and error/exception alike -- so the row is never left at a stale mid-session
            // value once the drain thread is actually done writing to it, whichever way it ended.
            refinalize()
            try {
                writer.close()
            } catch (_: Throwable) {}
            try {
                target.close()
            } catch (_: Throwable) {}
            synchronized(lock) {
                activeDrainThread = null
                activeTarget = null
                activeWriter = null
            }
        }
    }

    companion object {
        const val APP_FILE_PREFIX = "blackbox_"
        const val DEFAULT_DRAIN_CHUNK_SIZE_BYTES = 4096
        private const val POLL_INTERVAL_MILLIS = 50L
        private const val FINAL_DRAIN_TIMEOUT_NANOS = 5_000_000_000L // 5 seconds

        /** Throttle for periodic mid-recording MediaStore re-finalization (issue #140). */
        private const val REFINALIZE_INTERVAL_NANOS = 5_000_000_000L // 5 seconds

        fun generateDisplayName(date: Date, extension: String = StreamingAacWriter.FILE_EXTENSION): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            return "${APP_FILE_PREFIX}${dateFormat.format(date)}_forward.$extension"
        }
    }
}
