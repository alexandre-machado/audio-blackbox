package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioCaptureEngine
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.FormatSegment
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
    data class Error(val reason: ForwardRecordingFailureReason, val message: String, val exception: Throwable? = null) : ForwardRecordingState
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
    private val config: AudioConfig = AudioConfig(),
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
    private val configProvider: (() -> AudioConfig)? = null,
    private val errorLogFile: java.io.File? = null,
    // Issue #322: the ring buffer's own record of which format each byte range was recorded in --
    // `RingBuffer.activeSegments()`, the same single source of truth `ExportEngine` already drives
    // its converter from, deliberately not a second guess at "what is the config right now".
    // Re-sampled on every drain iteration (a lambda, not a value captured at start()) so a preset
    // change that lands mid-session is seen; `null` means "no segment record available", which
    // keeps the legacy `AudioCaptureEngine`-free constructor behaving exactly as before.
    private val segmentsProvider: (() -> List<FormatSegment>)? = null,
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
        errorLogFile = null,
        segmentsProvider = { engine.activeSegments() ?: emptyList() },
    )

    private val _state = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
    val state: StateFlow<ForwardRecordingState> = _state.asStateFlow()

    private var stateValue: ForwardRecordingState
        get() = _state.value
        set(value) {
            if (value is ForwardRecordingState.Error) {
                logExportError(errorLogFile, clock, "ForwardRecordingEngine", value.reason.name, value.message, value.exception)
            }
            _state.value = value
        }

    private val lock = Any()
    private var activeDrainThread: Thread? = null
    private var activeTarget: StreamingExportTarget? = null
    private var activeWriter: StreamingAudioWriter? = null
    private val stopRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private var wakeUpLatch = CountDownLatch(0)

    fun acknowledgeTerminalState() {
        synchronized(lock) {
            val current = stateValue
            if (current is ForwardRecordingState.Success || current is ForwardRecordingState.Error) {
                stateValue = ForwardRecordingState.Idle
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
                stateValue = err
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
                stateValue = err
                return err
            }

            val currentConfig = configProvider?.invoke() ?: config
            val writer = try {
                writerFactory(target, currentConfig)
            } catch (e: Exception) {
                target.close()
                val err = ForwardRecordingState.Error(
                    ForwardRecordingFailureReason.SINK_OPEN_FAILED,
                    "Failed to create streaming writer: ${e.message}",
                )
                stateValue = err
                return err
            }

            stopRequested.set(false)
            cancelRequested.set(false)
            wakeUpLatch = CountDownLatch(1)
            activeTarget = target
            activeWriter = writer

            val sessionStartMillis = clock()
            val initialGaps = gapsProvider()

            stateValue = ForwardRecordingState.Recording(displayName, 0L)

            val drainThread = Thread({
                // `currentConfig` is the format the writer above was configured with, i.e. the
                // format this file declares -- so it is also the format every drained chunk must
                // be converted into (issue #322).
                drainLoop(displayName, startCursor, target, writer, sessionStartMillis, initialGaps, currentConfig)
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

    /**
     * Per-session format reconciliation for the forward-recording drain (issue #322).
     *
     * The forward path writes into one long-lived encoder configured once, at session start, with
     * the session's declared format ([sessionConfig]) -- that is the shape of the container, and
     * re-configuring an open `MediaCodec`/`MediaMuxer` mid-file would change the file's shape
     * rather than fix anything. So the reconciliation goes the other way, exactly as the retro
     * export path already does it: every drained chunk is converted *into* [sessionConfig] before
     * the writer ever sees it, keeping one format per file.
     *
     * **Converter state survives chunk boundaries within a segment.** [PcmAudioConverter] is
     * explicitly streaming and stateful (fractional resampling phase plus the previous input
     * frame); a fresh instance per chunk would reintroduce the phase drift and boundary pops it
     * tracks that state to avoid. One converter is held per source format and only retired --
     * after a [PcmAudioConverter.flush] -- when the source format actually changes.
     *
     * ## This relies on one chunk never straddling a format boundary
     * It does not enforce that itself, because [cc.machado.audioblackbox.audio.RingBuffer.readSince]
     * already guarantees it (issue #194: "returned chunks are bounded to segment boundaries so that
     * each chunk contains audio from exactly one `AudioConfig`"). An earlier draft of this class
     * clamped the read size again on this side; mutation testing showed that clamp could be deleted
     * with no test failing anywhere, because it could never bind -- untestable duplicate defence,
     * so it was removed rather than shipped. The dependency is real though, so it is pinned by a
     * test of its own: `ForwardRecordingFormatBoundaryTest.readSince never returns a chunk
     * spanning two formats`.
     */
    private class ForwardFormatReconciler(
        private val sessionConfig: AudioConfig,
        private val segmentsProvider: (() -> List<FormatSegment>)?,
    ) {
        private var converter: PcmAudioConverter? = null
        private var converterSource: AudioConfig? = null

        /** Segments covering the live window, oldest first. Empty when there is no record. */
        private fun segments(): List<FormatSegment> =
            segmentsProvider?.invoke()?.sortedBy { it.startOffset } ?: emptyList()

        /**
         * The format `cursor`'s bytes were recorded in.
         *
         * [NoRecord] means there is no segment record at all -- the legacy
         * `AudioCaptureEngine`-free constructor, where the session is single-format by definition
         * and nothing is converted. [Unresolvable] means there *is* a record but it no longer
         * covers `cursor`: see [reconcile] for why that must not be guessed at.
         */
        private sealed interface SourceFormat {
            data class Known(val config: AudioConfig) : SourceFormat
            data object NoRecord : SourceFormat
            data object Unresolvable : SourceFormat
        }

        private fun sourceAt(cursor: Long): SourceFormat {
            val segs = segments()
            if (segs.isEmpty()) return SourceFormat.NoRecord
            val covering = segs.lastOrNull { it.startOffset <= cursor } ?: return SourceFormat.Unresolvable
            return SourceFormat.Known(covering.config)
        }

        /**
         * Converts [bytes] (read at [cursor]) into [sessionConfig], returning everything that must
         * be written: any tail flushed out of a retiring converter, followed by the converted
         * chunk. Identity when the recorded format already matches, so a single-format session
         * allocates and copies nothing.
         *
         * Returns `null` when the source format cannot be resolved, which the drain loop surfaces
         * as [ForwardRecordingFailureReason.CURSOR_LAPPED].
         *
         * ## Why an unresolvable format is an error and not a fallback (issue #322, `@rev` finding 4)
         * This used to end with `?: segs.first().config` -- if no segment covered `cursor`, it
         * converted the chunk from the *oldest surviving* segment's format instead. That is a guess,
         * and a wrong guess here writes real audio converted from the wrong source rate into a file
         * that declares it correctly: quietly wrong audio, with no error anywhere. That is precisely
         * the failure class this issue exists to close, so reintroducing it one file over would be
         * indefensible.
         *
         * `@rev` proposed resolving through `RingBuffer.formatAt` instead, on the grounds that it is
         * window-independent. **Measured, that is not a fix**: `oldestCursor()` *is*
         * `oldestAvailableLocked()`, so a segment leaves `activeSegments`' window and gets pruned
         * from `segments` at the same instant, and `formatAt` carries the identical
         * `?: segments.first().config` fallback. Both strategies return the same correct answer at
         * every lap depth where the covering segment survives, and the same wrong answer once it
         * does not. The API choice was never the defect; the silent fallback was.
         *
         * When it does fire, the information is genuinely gone -- the segment describing those
         * bytes has been pruned -- so there is nothing to convert *from* and no correct output to
         * produce. Failing loudly is the only honest option, and it is what this class already does
         * one layer up for [ReadSinceResult.Lapped]: the next `readSince` would report `Lapped`
         * anyway, so this surfaces the same condition one chunk earlier instead of writing a
         * corrupt chunk first.
         */
        fun reconcile(cursor: Long, bytes: ByteArray): List<ByteArray>? {
            val source = when (val resolved = sourceAt(cursor)) {
                is SourceFormat.Known -> resolved.config
                SourceFormat.NoRecord -> return listOf(bytes)
                SourceFormat.Unresolvable -> return null
            }
            val needsConversion = source.sampleRateHz != sessionConfig.sampleRateHz ||
                source.channelCount != sessionConfig.channelCount

            val out = mutableListOf<ByteArray>()
            if (converterSource != source) {
                // Boundary crossing: drain the outgoing converter's held frame before switching,
                // so nothing recorded before the boundary is lost at it.
                converter?.flush()?.takeIf { it.isNotEmpty() }?.let { out += it }
                converter = if (needsConversion) PcmAudioConverter(source, sessionConfig) else null
                converterSource = source
            }
            val active = converter
            out += if (active != null) active.convert(bytes) else bytes
            return out.filter { it.isNotEmpty() }
        }

        /** Final tail of the last converter, for the end of the session. */
        fun flush(): ByteArray? {
            val tail = converter?.flush()
            converter = null
            converterSource = null
            return tail?.takeIf { it.isNotEmpty() }
        }
    }

    private fun drainLoop(
        displayName: String,
        initialCursor: Long,
        target: StreamingExportTarget,
        writer: StreamingAudioWriter,
        sessionStartMillis: Long,
        initialGaps: List<PauseGap>,
        sessionConfig: AudioConfig,
    ) {
        var cursor = initialCursor
        var totalBytesDrained = 0L
        val handledGaps = initialGaps.toMutableSet()
        var lastRefinalizeAtNanos = System.nanoTime()
        val reconciler = ForwardFormatReconciler(sessionConfig, segmentsProvider)

        // Writes `bytes` (already in sessionConfig) and keeps the reported byte count in step with
        // what actually reached the file, not with how much raw PCM was read -- those differ by the
        // conversion ratio whenever a segment predates the session's format.
        fun emit(chunks: List<ByteArray>) {
            for (chunk in chunks) {
                writer.write(chunk)
                totalBytesDrained += chunk.size
            }
        }

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
                            stateValue = ForwardRecordingState.Error(
                                ForwardRecordingFailureReason.CAPTURE_NOT_ACTIVE,
                                "Capture stopped unexpectedly during forward recording",
                            )
                        }
                        return
                    }
                    is ReadSinceResult.Lapped -> {
                        if (cursor == initialCursor) {
                            // If we are at the initial cursor when starting the drain, the live rolling
                            // buffer advanced slightly while opening the sink / writer. Recover from the
                            // oldest available surviving audio cursor (issues #204, #218).
                            cursor = result.oldestAvailableCursor
                            continue
                        }
                        synchronized(lock) {
                            stateValue = ForwardRecordingState.Error(
                                ForwardRecordingFailureReason.CURSOR_LAPPED,
                                "Forward writer fell behind: ${result.lostBytes} bytes lost",
                            )
                        }
                        return
                    }
                    is ReadSinceResult.StreamReset -> {
                        synchronized(lock) {
                            stateValue = ForwardRecordingState.Error(
                                ForwardRecordingFailureReason.STREAM_RESET,
                                "Capture stream was reset under cursor $cursor",
                            )
                        }
                        return
                    }
                    is ReadSinceResult.Data -> {
                        if (result.bytes.isNotEmpty()) {
                            // A null reconcile means the segment describing these bytes was pruned
                            // between `readSince` returning them and the reconciler resolving their
                            // format, so there is nothing to convert *from* (issue #322, `@rev`
                            // finding 4). Same condition, same reason and same exit as the
                            // `Lapped` branch above -- the next `readSince` would report `Lapped`
                            // anyway; this just surfaces it one chunk earlier, instead of writing a
                            // chunk converted from a guessed format first.
                            val reconciled = reconciler.reconcile(cursor, result.bytes)
                            if (reconciled == null) {
                                synchronized(lock) {
                                    stateValue = ForwardRecordingState.Error(
                                        ForwardRecordingFailureReason.CURSOR_LAPPED,
                                        "Forward writer fell behind: the recorded format at cursor " +
                                            "$cursor is no longer known",
                                    )
                                }
                                return
                            }
                            emit(reconciled)
                            cursor = result.nextCursor
                            synchronized(lock) {
                                if (_state.value is ForwardRecordingState.Recording) {
                                    stateValue = ForwardRecordingState.Recording(displayName, totalBytesDrained)
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
                    stateValue = ForwardRecordingState.Error(
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
                            // Unresolvable format on the clean-stop path stops the drain rather
                            // than failing the recording (issue #322, `@rev` finding 4). This
                            // deliberately matches how this same loop already treats `Lapped` --
                            // the `else` branch below -- because both mean "the tail is gone", and
                            // at this point a complete, correctly-labelled file has already been
                            // written. Turning a finished recording into an Error over a lost tail
                            // would destroy more than it protects. What must not happen, and no
                            // longer can, is writing that tail converted from a guessed format.
                            val reconciled = reconciler.reconcile(cursor, result.bytes)
                            if (reconciled == null) {
                                finalDrainDone = true
                            } else {
                                emit(reconciled)
                                cursor = result.nextCursor
                            }
                        }
                        if (result.remainingBytes == 0L) {
                            finalDrainDone = true
                        }
                    }
                    else -> finalDrainDone = true
                }
            }

            // Last converter's held boundary frame, before the encoder is closed for good.
            reconciler.flush()?.let { emit(listOf(it)) }

            writer.finish()
            target.finish()

            synchronized(lock) {
                stateValue = ForwardRecordingState.Success(displayName, totalBytesDrained)
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                stateValue = ForwardRecordingState.Error(
                    ForwardRecordingFailureReason.WRITE_FAILED,
                    "Forward recording write failed: ${t.message}",
                    t
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
