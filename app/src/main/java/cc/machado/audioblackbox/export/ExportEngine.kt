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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable lifecycle of one [ExportEngine.export] call. */
sealed interface ExportState {
    data object Idle : ExportState
    data object Exporting : ExportState
    data class Success(val displayName: String, val bytesWritten: Int) : ExportState
    data class Error(val reason: ExportFailureReason, val message: String) : ExportState
}

/** Why an export failed, so a caller can decide what to show/whether retrying makes sense. */
enum class ExportFailureReason {
    /** Capture is not running ([ExportEngine]'s cursor providers returned `null`), or nothing is
     * buffered yet (zero bytes between the oldest and write cursor). */
    NO_AUDIO_BUFFERED,

    /** [ExportSink.open] threw, e.g. MediaStore insert rejected, no space, permission denied. */
    SINK_OPEN_FAILED,

    /** Writing the header or payload to the sink's `OutputStream` threw. */
    WRITE_FAILED,

    /** The bounded drain fell behind the capture writer and the ring buffer wrapped past the
     * cursor it was reading from ([ReadSinceResult.Lapped]) -- PCM this export needed no longer
     * exists anywhere. Surfaced rather than silently exported as a shorter file (issue #29). */
    CURSOR_LAPPED,

    /** The capture buffer was cleared (e.g. `AudioCaptureEngine.stop()`) while a bounded drain was
     * still reading it ([ReadSinceResult.StreamReset]). Surfaced rather than silently exported as
     * a shorter file (issue #29). */
    STREAM_RESET,

    /** [ExportEngine.cancel] was called before the write finished. */
    CANCELLED,

    /** [ExportEngine.export] was called while a previous call on this same instance was still
     * running (double-tap on the notification's Save action, or an OS-redelivered Intent). */
    EXPORT_ALREADY_IN_PROGRESS,

    /** Something other than the failure modes above threw while exporting (a future regression,
     * an `OutOfMemoryError` on a very large snapshot, ...). Caught so [export] can honour its
     * "never throws" contract and [state] can never strand on [ExportState.Exporting] -- see
     * [export]'s doc comment and PR #28 review round 3 (`@rev` finding 5 / `@techlead`
     * adjudication item 1). */
    UNEXPECTED_FAILURE,
}

/**
 * Orchestrates one "Save" action end to end: plan the requested window against the ring buffer's
 * cursors -> drain it in bounded chunks, filling interruption gaps with silence as it goes ->
 * encode via [payloadEncoder] -> write to an [ExportSink] (issue #5, encoder made pluggable in
 * issue #32, bounded cursor drain in issue #72). Gap filling and encoding both happen incrementally
 * over the same [BoundedExportPlan] -- neither the lossy AAC encoder nor a future encoder sees
 * anything but a single already-correct timeline, just delivered as chunks instead of one array
 * (see [BoundedExportPlanner]/[PayloadEncoder]'s docs).
 *
 * ## Why this is not [cc.machado.audioblackbox.audio.RingBuffer.snapshot] anymore (issue #72)
 * `snapshot(durationMillis)` allocates a fresh destination array the size of the whole requested
 * window on top of the ring buffer's own same-size backing array -- peak memory at save time was
 * 2x the retention window by construction, which OOMs on a real device at the top of the range
 * (issue #72's device evidence). This class instead reads [cc.machado.audioblackbox.audio.RingBuffer.writeCursor]/
 * [cc.machado.audioblackbox.audio.RingBuffer.oldestCursor] once to fix the window, computes a
 * [BoundedExportPlan] from cursors and gap timestamps alone (no PCM touched yet), and only then
 * drains it via [readSinceProvider] in chunks bounded by [drainChunkSizeBytes] -- the same
 * primitive [ForwardRecordingEngine] already uses for its live drain (issue #51/#54), reused here
 * rather than inventing a second drain protocol.
 *
 * Pure Kotlin plus `StateFlow` for observability -- no direct Android dependency beyond that --
 * so the whole plan/gap-fill orchestration is unit-testable without a device (the concrete
 * [payloadEncoder]'s own Android-only work, e.g. [AacPayloadEncoder]'s `MediaCodec`/`MediaMuxer`
 * use, is covered by the instrumented tier instead -- see `docs/testing/tiers.md`).
 * [readSinceProvider]/[writeCursorProvider]/[oldestCursorProvider]/[estimateTimestampProvider]/
 * [gapsProvider] mirror [cc.machado.audioblackbox.audio.AudioCaptureEngine]'s own bounded-read
 * surface as plain functions (the same function-seam pattern `AudioCaptureEngine` already uses for
 * `audioRecordFactory`), and [ExportSink] is the only seam that touches MediaStore.
 *
 * [export] is blocking and does real I/O; callers are expected to invoke it off the calling
 * thread's UI/capture work (e.g. from `Dispatchers.IO`), the same way
 * [cc.machado.audioblackbox.service.RecorderService] already dispatches `engine.stop()` off its
 * main thread. It never touches [cc.machado.audioblackbox.audio.RingBuffer]'s lock itself, and
 * every individual [readSinceProvider] call it makes is bounded to [drainChunkSizeBytes] -- a
 * single call never blocks the capture writer for longer than one chunk's copy, regardless of how
 * large the retention window is.
 */
class ExportEngine(
    private val config: AudioConfig,
    private val readSinceProvider: (cursor: Long, maxBytes: Int) -> ReadSinceResult?,
    private val writeCursorProvider: () -> Long?,
    private val oldestCursorProvider: () -> Long?,
    private val estimateTimestampProvider: (Long) -> Long?,
    private val gapsProvider: () -> List<PauseGap>,
    private val sink: ExportSink,
    private val payloadEncoder: PayloadEncoder,
    private val drainChunkSizeBytes: Int = DEFAULT_DRAIN_CHUNK_SIZE_BYTES,
    private val segmentsProvider: (() -> List<FormatSegment>?)? = null,
) {
    constructor(
        engine: AudioCaptureEngine,
        config: AudioConfig,
        sink: ExportSink,
        payloadEncoder: PayloadEncoder,
        drainChunkSizeBytes: Int = DEFAULT_DRAIN_CHUNK_SIZE_BYTES,
    ) : this(
        config = config,
        readSinceProvider = { cursor, maxBytes -> engine.readSince(cursor, maxBytes) },
        writeCursorProvider = { engine.writeCursor() },
        oldestCursorProvider = { engine.oldestCursor() },
        estimateTimestampProvider = { offset -> engine.estimateTimestamp(offset) },
        gapsProvider = { engine.gaps.value },
        sink = sink,
        payloadEncoder = payloadEncoder,
        drainChunkSizeBytes = drainChunkSizeBytes,
        segmentsProvider = { engine.activeSegments() },
    )

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    @Volatile private var cancelRequested = false

    // Guards the check-then-set of `_state`/`cancelRequested` at the top of `export()` so two
    // concurrent calls on this instance (double-tap, redelivered Intent) can't both observe
    // "not exporting", both proceed, and race each other's `cancelRequested` reset and `_state`
    // transitions (PR #28 review, `@sec` finding 2 / `@techlead` adjudication finding 5).
    private val exportLock = Any()

    /** Requests cancellation of an in-flight [export]. No-op if none is running. Checked between
     * write chunks, so a large export stops promptly rather than at the very end.
     *
     * Deliberately deferred to Module 4 (#6): no UI/notification action calls this yet, so it is
     * currently unreachable in the shipped app. The method and the [ExportState.CANCELLED] path
     * it drives exist and are unit-tested because the cancel-mid-write behavior they exercise is
     * load-bearing for [runExport]'s error-handling shape, not because a cancel affordance ships
     * in this module -- see PR #28 review, `@techlead` adjudication finding 4. */
    fun cancel() {
        cancelRequested = true
    }

    /** Resets [state] back to [ExportState.Idle] once a terminal [ExportState.Success]/
     * [ExportState.Error] outcome has actually been surfaced to the user (e.g. rendered once in
     * the persistent notification) -- otherwise nothing ever writes [ExportState.Idle] back, and
     * a later, unrelated notification refresh (a phone-call pause hours after a save) would
     * reassert that stale outcome indefinitely, for the rest of the service's lifetime (PR #28
     * review, `@sec`/`@rev` finding, `@techlead` round-3 adjudication item 2).
     *
     * No-op unless [state] is currently [ExportState.Success] or [ExportState.Error] -- in
     * particular it never clears [ExportState.Idle] or [ExportState.Exporting], so calling this
     * after a newer export has already started can't stomp on it. Callers must only invoke this
     * after actually showing the outcome to the user; calling it immediately after [export]
     * returns would mean the outcome is never visible at all -- see
     * [cc.machado.audioblackbox.service.RecorderService]'s collector for the deliberate visible
     * window before it calls this. */
    fun acknowledgeTerminalState() {
        synchronized(exportLock) {
            val current = _state.value
            if (current is ExportState.Success || current is ExportState.Error) {
                _state.value = ExportState.Idle
            }
        }
    }

    /**
     * Runs one export of the last [durationMillis] of audio, labeling the filename with
     * [minutesLabel] (e.g. `30` for `..._30min.wav`), or with [secondsLabel] instead when it is
     * non-null (e.g. `45` for `..._45s.wav`) -- see [filenameFor]'s doc. Blocking; always leaves [state] (and its
     * own return value) in [ExportState.Success] or [ExportState.Error] -- never throws:
     * [runExport]/[writeAndFinish] catch any unexpected `Throwable` internally and convert it to
     * [ExportFailureReason.UNEXPECTED_FAILURE]; the `try`/`finally` below is the backstop that
     * still guarantees [state] is set even if that internal guarantee were ever violated by a
     * future change -- without it, an exception escaping [runExport] would leave [state] stranded
     * on [ExportState.Exporting] forever, which -- because the check above keys off exactly that
     * state -- would permanently reject every later [export] call on this instance (PR #28
     * review, `@rev` finding 5 / `@techlead` round-3 adjudication item 1).
     *
     * If an export is already running on this instance, returns
     * [ExportFailureReason.EXPORT_ALREADY_IN_PROGRESS] immediately without touching
     * [readSinceProvider]/[sink] -- "check before acting" dedup rather than letting two exports interleave.
     */
    fun export(durationMillis: Long, minutesLabel: Int, secondsLabel: Int? = null): ExportState {
        synchronized(exportLock) {
            if (_state.value is ExportState.Exporting) {
                return ExportState.Error(
                    ExportFailureReason.EXPORT_ALREADY_IN_PROGRESS,
                    "an export is already in progress",
                )
            }
            cancelRequested = false
            _state.value = ExportState.Exporting
        }
        var result: ExportState = ExportState.Error(
            ExportFailureReason.UNEXPECTED_FAILURE,
            "export did not complete",
        )
        try {
            result = runExport(durationMillis, minutesLabel, secondsLabel)
        } finally {
            _state.value = result
        }
        return result
    }

    private fun runExport(durationMillis: Long, minutesLabel: Int, secondsLabel: Int?): ExportState {
        return try {
            // Fix the window purely from cursors, before touching a single PCM byte (issue #72):
            // [oldestCursor, writeCursor) is everything currently buffered, which is naturally at
            // least as much raw audio as `durationMillis` needs plus gap padding -- the same
            // "request extra raw audio up front to compensate for gap time" intent this class used
            // to implement via `snapshot(durationMillis + paddingMillis)`, just expressed as "use
            // the whole buffered window" instead of "ask for a padded duration", since a
            // [BoundedExportPlan] costs nothing to compute over cursors alone.
            val writeCursor = writeCursorProvider()
                ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "capture is not running")
            val oldestCursor = oldestCursorProvider()
                ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "capture is not running")
            val rawLength = writeCursor - oldestCursor
            if (rawLength <= 0L) {
                return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "nothing buffered yet")
            }
            val windowStart = estimateTimestampProvider(oldestCursor)
                ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "capture is not running")

            val gaps = gapsProvider()
            val activeSegs = segmentsProvider?.invoke() ?: emptyList()
            val targetConfig = activeSegs.lastOrNull()?.config ?: config
            val plan = BoundedExportPlanner.plan(
                startCursor = oldestCursor,
                rawLength = rawLength,
                windowStart = windowStart,
                gaps = gaps,
                segments = activeSegs,
                targetConfig = targetConfig,
                targetDurationMillis = durationMillis,
            )
            val displayName = filenameFor(windowStart, minutesLabel, secondsLabel)

            val target = try {
                sink.open(displayName, payloadEncoder.mimeType)
            } catch (e: IOException) {
                return ExportState.Error(ExportFailureReason.SINK_OPEN_FAILED, e.message ?: "sink open failed")
            }

            val reader = BoundedExportReader(plan, readSinceProvider, drainChunkSizeBytes)
            writeAndFinish(target, plan, reader, displayName)
        } catch (e: CancellationException) {
            throw e // preserve normal coroutine cancellation semantics, don't swallow it as a failure
        } catch (e: Throwable) {
            // Anything unexpected (a future regression in BoundedExportPlanner, a throw from
            // gapsProvider()/one of the cursor providers, ...) must still leave export() free to
            // run again on the next call, not silently stranded -- see export()'s doc comment.
            ExportState.Error(ExportFailureReason.UNEXPECTED_FAILURE, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeAndFinish(
        target: ExportTarget,
        plan: BoundedExportPlan,
        reader: BoundedExportReader,
        displayName: String,
    ): ExportState {
        var writeFailure: Throwable? = null
        var failureReason: ExportFailureReason? = null
        try {
            target.outputStream.use { out ->
                // payloadEncoder.encode() owns the whole file format (header/frames/container) --
                // see PayloadEncoder's doc. This also covers encode failures the same way it
                // already covered write failures: any Throwable here still aborts the sink below
                // (issue #32 requirement: a pending MediaStore row must not survive a failed
                // encode). `reader` pulls chunks bounded by `drainChunkSizeBytes` from the ring
                // buffer as the encoder asks for them -- the encoder never sees (and this class
                // never allocates) a buffer proportional to the whole plan (issue #72).
                payloadEncoder.encode(plan.targetConfig, plan.totalOutputBytes, reader, out) { cancelRequested }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BoundedExportDrainException) {
            // The bounded drain fell behind the writer (Lapped), the buffer was cleared under it
            // (StreamReset), or capture stopped mid-drain -- each already carries the specific
            // ExportFailureReason a caller needs (issue #29: surfaced, never a silently shorter
            // file), so preserve it instead of collapsing everything to WRITE_FAILED below.
            writeFailure = e
            failureReason = e.reason
        } catch (e: Throwable) {
            // Broadened from IOException-only (round 2) to any Throwable: a non-IOException here
            // must still abort the target below, not propagate and skip it (PR #28 review round 3,
            // same finding as export()'s try/finally).
            writeFailure = e
        } finally {
            // Zeroes whatever chunk the reader last handed the encoder, regardless of outcome --
            // the same "stop means stop" residue discipline the old rawSnapshot/payload zeroing
            // implemented, just scoped to one chunk at a time instead of the whole window (issue
            // #72).
            reader.close()
        }

        val bytesWritten = plan.totalOutputBytes.toInt()
        val state = when {
            writeFailure != null -> {
                target.abort()
                ExportState.Error(
                    failureReason ?: ExportFailureReason.WRITE_FAILED,
                    writeFailure.message ?: writeFailure.javaClass.simpleName,
                )
            }
            cancelRequested -> {
                target.abort()
                ExportState.Error(ExportFailureReason.CANCELLED, "export cancelled")
            }
            else -> {
                target.commit()
                ExportState.Success(displayName, bytesWritten)
            }
        }
        return state
    }

    /**
     * [secondsLabel], when non-null, overrides [minutesLabel] in the filename with whole-seconds
     * granularity (`..._45s.m4a` instead of `..._0min.m4a`) -- issue #129 follow-up (`@techlead`
     * round-2 finding): a floored `0min` name for a genuinely sub-minute save is technically
     * non-overstating but useless for identifying the file later in an evidentiary product.
     * `null` (the default every existing caller still passes) preserves the original
     * `..._Nmin.` naming exactly. Callers are expected to pass [secondsLabel] only when
     * [minutesLabel] itself resolved to `0` (see
     * [cc.machado.audioblackbox.service.RecorderService.resolveSavedSeconds]'s doc) -- this method
     * does not re-derive that condition itself, it only trusts what it is given.
     */
    private fun filenameFor(startTimestampMillis: Long, minutesLabel: Int, secondsLabel: Int?): String {
        val formatter = SimpleDateFormat(FILENAME_TIMESTAMP_PATTERN, Locale.US)
        val timestamp = formatter.format(Date(startTimestampMillis))
        val durationSuffix = if (secondsLabel != null) "${secondsLabel}s" else "${minutesLabel}min"
        return "blackbox_${timestamp}_${durationSuffix}.${payloadEncoder.fileExtension}"
    }

    private companion object {
        const val FILENAME_TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"

        // Matches ForwardRecordingEngine's DEFAULT_DRAIN_CHUNK_SIZE_BYTES (issue #51/#54): reusing
        // the same bound keeps this class's drain chunking behaviorally identical to the live-drain
        // primitive it borrows from (issue #72), not a second tuned-independently value.
        const val DEFAULT_DRAIN_CHUNK_SIZE_BYTES = 4096
    }
}
