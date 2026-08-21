package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.AudioSnapshot
import cc.machado.audioblackbox.audio.PauseGap
import java.io.IOException
import java.io.OutputStream
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
    /** [ExportEngine]'s `snapshotProvider` returned `null` (capture not running) or an empty
     * snapshot (nothing buffered yet). */
    NO_AUDIO_BUFFERED,

    /** [ExportSink.open] threw, e.g. MediaStore insert rejected, no space, permission denied. */
    SINK_OPEN_FAILED,

    /** Writing the header or payload to the sink's `OutputStream` threw. */
    WRITE_FAILED,

    /** [ExportEngine.cancel] was called before the write finished. */
    CANCELLED,

    /** [ExportEngine.export] was called while a previous call on this same instance was still
     * running (double-tap on the notification's Save action, or an OS-redelivered Intent -- the
     * same class of duplicate dispatch this codebase already guards
     * [cc.machado.audioblackbox.service.RecorderService]'s `requestAudioFocus()` against). */
    EXPORT_ALREADY_IN_PROGRESS,

    /** Something other than the failure modes above threw while exporting (a future regression,
     * an `OutOfMemoryError` on a very large snapshot, ...). Caught so [export] can honour its
     * "never throws" contract and [state] can never strand on [ExportState.Exporting] -- see
     * [export]'s doc comment and PR #28 review round 3 (`@rev` finding 5 / `@techlead`
     * adjudication item 1). */
    UNEXPECTED_FAILURE,
}

/**
 * Orchestrates one "Save" action end to end: snapshot the ring buffer -> fill interruption gaps
 * with silence -> encode via [payloadEncoder] -> write to an [ExportSink] (issue #5, encoder made
 * pluggable in issue #32). Gap filling always happens on raw PCM, once, *before* [payloadEncoder]
 * ever runs -- neither the lossy AAC encoder nor a future encoder sees anything but a single
 * already-correct timeline (see [GapFiller]/[PayloadEncoder]'s docs).
 *
 * Pure Kotlin plus `StateFlow` for observability -- no direct Android dependency beyond that --
 * so the whole snapshot/gap-fill orchestration is unit-testable without a device (the concrete
 * [payloadEncoder]'s own Android-only work, e.g. [AacPayloadEncoder]'s `MediaCodec`/`MediaMuxer`
 * use, is covered by the instrumented tier instead -- see `docs/testing/tiers.md`).
 * [snapshotProvider]/[gapsProvider] mirror
 * [cc.machado.audioblackbox.audio.AudioCaptureEngine.snapshot]/`.gaps.value` as plain functions
 * (the same function-seam pattern `AudioCaptureEngine` already uses for `audioRecordFactory`),
 * and [ExportSink] is the only seam that touches MediaStore.
 *
 * [export] is blocking and does real I/O; callers are expected to invoke it off the calling
 * thread's UI/capture work (e.g. from `Dispatchers.IO`), the same way
 * [cc.machado.audioblackbox.service.RecorderService] already dispatches `engine.stop()` off its
 * main thread. It never touches [cc.machado.audioblackbox.audio.RingBuffer]'s lock itself --
 * only [snapshotProvider] (backed by `RingBuffer.snapshot`) does, and that call is bounded and
 * returns before any of this class's work (gap fill, header, file write) begins, so capture is
 * never blocked by anything this class does after the snapshot returns.
 */
class ExportEngine(
    private val config: AudioConfig,
    private val snapshotProvider: (Long) -> AudioSnapshot?,
    private val gapsProvider: () -> List<PauseGap>,
    private val sink: ExportSink,
    private val payloadEncoder: PayloadEncoder,
) {
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
     * [minutesLabel] (e.g. `30` for `..._30min.wav`). Blocking; always leaves [state] (and its
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
     * [snapshotProvider]/[sink] -- mirrors [cc.machado.audioblackbox.service.AudioFocusTracker]'s
     * "check before acting" dedup rather than letting two exports interleave.
     */
    fun export(durationMillis: Long, minutesLabel: Int): ExportState {
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
            result = runExport(durationMillis, minutesLabel)
        } finally {
            _state.value = result
        }
        return result
    }

    private fun runExport(durationMillis: Long, minutesLabel: Int): ExportState {
        // Tracked outside the try below (rather than declared inside it) so the catch(Throwable)
        // branch can still zero it if it was ever assigned -- an exception from gapsProvider()
        // itself, or from snapshotProvider(), means there's nothing to zero yet, which is fine.
        var rawSnapshotForCleanup: AudioSnapshot? = null
        return try {
            val gaps = gapsProvider()
            // Request extra raw audio up front to compensate for gap time: RingBuffer.snapshot()
            // returns audio-time, not wall-clock time (see GapFiller's doc), so without padding a
            // window containing interruptions would come back short.
            val paddingMillis = gaps.sumOf { it.durationMillis }.coerceAtLeast(0L)
            val rawSnapshot = snapshotProvider(durationMillis + paddingMillis)
                ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "capture is not running")
            rawSnapshotForCleanup = rawSnapshot
            if (rawSnapshot.data.isEmpty()) {
                return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "nothing buffered yet")
            }

            val payload = GapFiller.fill(rawSnapshot, gaps, config, durationMillis)
            // rawSnapshot.data is a fresh copy of raw mic PCM (RingBuffer.snapshot()); GapFiller.fill
            // has already consumed it into payload, so nothing here needs it again. Zero it rather
            // than leaving it to GC, matching the "stop means stop" residue posture RingBuffer itself
            // already holds to (see RingBuffer.clear).
            java.util.Arrays.fill(rawSnapshot.data, 0)
            val displayName = filenameFor(rawSnapshot.startTimestampMillis, minutesLabel)

            val target = try {
                sink.open(displayName, payloadEncoder.mimeType)
            } catch (e: IOException) {
                return ExportState.Error(ExportFailureReason.SINK_OPEN_FAILED, e.message ?: "sink open failed")
            }

            writeAndFinish(target, payload, displayName)
        } catch (e: CancellationException) {
            throw e // preserve normal coroutine cancellation semantics, don't swallow it as a failure
        } catch (e: Throwable) {
            // Anything unexpected (a future regression in GapFiller, an OutOfMemoryError on a very
            // large snapshot, a throw from gapsProvider()/snapshotProvider() itself, ...) must
            // still leave rawSnapshot.data zeroed (if it exists yet) and export() free to run
            // again on the next call, not silently stranded -- see export()'s doc comment.
            rawSnapshotForCleanup?.data?.let { java.util.Arrays.fill(it, 0) }
            ExportState.Error(ExportFailureReason.UNEXPECTED_FAILURE, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeAndFinish(target: ExportTarget, payload: ByteArray, displayName: String): ExportState {
        var writeFailure: Throwable? = null
        try {
            target.outputStream.use { out ->
                // payloadEncoder.encode() owns the whole file format (header/frames/container) --
                // see PayloadEncoder's doc. This also covers encode failures the same way it
                // already covered write failures: any Throwable here still aborts the sink below
                // (issue #32 requirement: a pending MediaStore row must not survive a failed
                // encode).
                payloadEncoder.encode(config, payload, out) { cancelRequested }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Broadened from IOException-only (round 2) to any Throwable: a non-IOException here
            // must still abort the target and zero payload below, not propagate and skip both
            // (PR #28 review round 3, same finding as export()'s try/finally).
            writeFailure = e
        }

        val bytesWritten = payload.size
        val state = when {
            writeFailure != null -> {
                target.abort()
                ExportState.Error(
                    ExportFailureReason.WRITE_FAILED,
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
        // payload is a fresh copy of raw mic PCM, already written (or attempted) to the sink on
        // every path above -- nothing needs it again regardless of outcome. Zero it rather than
        // leaving it to GC (see the matching rawSnapshot.data zeroing in runExport).
        java.util.Arrays.fill(payload, 0)
        return state
    }

    private fun filenameFor(startTimestampMillis: Long, minutesLabel: Int): String {
        val formatter = SimpleDateFormat(FILENAME_TIMESTAMP_PATTERN, Locale.US)
        val timestamp = formatter.format(Date(startTimestampMillis))
        return "blackbox_${timestamp}_${minutesLabel}min.${payloadEncoder.fileExtension}"
    }

    private companion object {
        const val FILENAME_TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"
    }
}
