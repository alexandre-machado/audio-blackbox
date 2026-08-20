package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.AudioSnapshot
import cc.machado.audioblackbox.audio.PauseGap
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
}

/**
 * Orchestrates one "Save" action end to end: snapshot the ring buffer -> fill interruption gaps
 * with silence -> encode as WAV -> write to an [ExportSink] (issue #5).
 *
 * Pure Kotlin plus `StateFlow` for observability -- no direct Android dependency beyond that --
 * so the whole snapshot/gap-fill/encode path is unit-testable without a device;
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

    /**
     * Runs one export of the last [durationMillis] of audio, labeling the filename with
     * [minutesLabel] (e.g. `30` for `..._30min.wav`). Blocking; always leaves [state] (and its
     * own return value) in [ExportState.Success] or [ExportState.Error] -- never throws.
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
        val result = runExport(durationMillis, minutesLabel)
        _state.value = result
        return result
    }

    private fun runExport(durationMillis: Long, minutesLabel: Int): ExportState {
        val gaps = gapsProvider()
        // Request extra raw audio up front to compensate for gap time: RingBuffer.snapshot()
        // returns audio-time, not wall-clock time (see GapFiller's doc), so without padding a
        // window containing interruptions would come back short.
        val paddingMillis = gaps.sumOf { it.durationMillis }.coerceAtLeast(0L)
        val rawSnapshot = snapshotProvider(durationMillis + paddingMillis)
            ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "capture is not running")
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
            sink.open(displayName)
        } catch (e: IOException) {
            return ExportState.Error(ExportFailureReason.SINK_OPEN_FAILED, e.message ?: "sink open failed")
        }

        return writeAndFinish(target, payload, displayName)
    }

    private fun writeAndFinish(target: ExportTarget, payload: ByteArray, displayName: String): ExportState {
        var writeFailure: IOException? = null
        try {
            target.outputStream.use { out ->
                WavWriter.writeHeader(out, config, payload.size)
                writeInChunks(out, payload)
            }
        } catch (e: IOException) {
            writeFailure = e
        }

        val bytesWritten = payload.size
        val state = when {
            writeFailure != null -> {
                target.abort()
                ExportState.Error(ExportFailureReason.WRITE_FAILED, writeFailure.message ?: "write failed")
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

    private fun writeInChunks(out: OutputStream, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            if (cancelRequested) return
            val length = minOf(CHUNK_BYTES, payload.size - offset)
            out.write(payload, offset, length)
            offset += length
        }
    }

    private fun filenameFor(startTimestampMillis: Long, minutesLabel: Int): String {
        val formatter = SimpleDateFormat(FILENAME_TIMESTAMP_PATTERN, Locale.US)
        val timestamp = formatter.format(Date(startTimestampMillis))
        return "blackbox_${timestamp}_${minutesLabel}min.wav"
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val FILENAME_TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"
    }
}
