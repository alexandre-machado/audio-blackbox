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
    data class Error(val reason: ExportFailureReason, val message: String, val exception: Throwable? = null) : ExportState
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
    // Issue #385: lets runExport tell a saturated ring buffer (oldest byte actively being evicted
    // as new audio arrives -- a slow sink/encoder open can race the writer past it) from one that
    // has not wrapped yet (nothing evicted, no race possible). A provider that always returns
    // `null` disables the startup headroom below entirely.
    //
    // Deliberately **no default value** (`@rev` BLOCK review on PR #386): this parameter used to
    // default to `{ null }`, and `RecorderService`'s production `exportEngine` -- which calls this
    // primary constructor directly, not the secondary `constructor(engine: AudioCaptureEngine, ...)`
    // below that already wired this correctly -- silently kept that default, so the #385 fix never
    // actually ran on a real device despite shipping with green tests and CI. A missing default
    // here means the compiler rejects any future primary-constructor call site (production or
    // test) that forgets this the same way it already rejects one that forgets `sink` or
    // `payloadEncoder` -- see `buildExportEngine` in `RecorderService.kt` for the fixed call site.
    private val capacityBytesProvider: () -> Int?,
    private val estimateTimestampProvider: (Long) -> Long?,
    private val gapsProvider: () -> List<PauseGap>,
    private val sink: ExportSink,
    private val payloadEncoder: PayloadEncoder,
    private val drainChunkSizeBytes: Int = DEFAULT_DRAIN_CHUNK_SIZE_BYTES,
    private val segmentsProvider: (() -> List<FormatSegment>?)? = null,
    private val minExportDurationMillis: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val errorLogFile: java.io.File? = null,
    // Issue #322: fresh read of the capture config, for the one place [config] is still consulted
    // (the no-segments fallback in [runExport]). [config] is captured once at construction, and
    // since #194 a quality-preset change really can move sampleRateHz/channelCount underneath it --
    // so a constructor-captured copy is a stale-format trap even though nothing reaches it today.
    // Mirrors [ForwardRecordingEngine]'s `configProvider`. `null` keeps the old behavior.
    private val configProvider: (() -> AudioConfig)? = null,
    // Issue #344: when the cursor providers above report "capture is not running" (null), this
    // optionally distinguishes *why* -- "never started" (this returns null, e.g. CaptureState.Idle)
    // from "capture died unexpectedly" (a non-null description, e.g. AudioCaptureEngine's own
    // CaptureState.Error.message once its captureLoop routes an unexpected Throwable there instead
    // of falling back to Idle silently). `null` (the default, and every existing caller before this
    // issue) keeps the exact prior message, so this is purely additive.
    private val captureFailureDescriptionProvider: (() -> String?)? = null,
) {
    constructor(
        engine: AudioCaptureEngine,
        config: AudioConfig,
        sink: ExportSink,
        payloadEncoder: PayloadEncoder,
        drainChunkSizeBytes: Int = DEFAULT_DRAIN_CHUNK_SIZE_BYTES,
        minExportDurationMillis: Long = 0L,
        errorLogFile: java.io.File? = null,
    ) : this(
        config = config,
        readSinceProvider = { cursor, maxBytes -> engine.readSince(cursor, maxBytes) },
        writeCursorProvider = { engine.writeCursor() },
        oldestCursorProvider = { engine.oldestCursor() },
        capacityBytesProvider = { engine.capacityBytes() },
        estimateTimestampProvider = { offset -> engine.estimateTimestamp(offset) },
        gapsProvider = { engine.gaps.value },
        sink = sink,
        payloadEncoder = payloadEncoder,
        drainChunkSizeBytes = drainChunkSizeBytes,
        segmentsProvider = { engine.activeSegments() },
        minExportDurationMillis = minExportDurationMillis,
        errorLogFile = errorLogFile,
        captureFailureDescriptionProvider = {
            (engine.state.value as? cc.machado.audioblackbox.audio.CaptureState.Error)
                ?.let { "capture died unexpectedly (${it.reason}): ${it.message}" }
        },
    )

    /** "capture is not running", plus why if [captureFailureDescriptionProvider] can say (issue
     * #344) -- e.g. "capture died unexpectedly (UNEXPECTED_CAPTURE_FAILURE): ..." instead of the
     * bare message that reads identically whether capture was simply never started or died mid
     * session. */
    private fun captureNotRunningMessage(): String {
        val detail = captureFailureDescriptionProvider?.invoke()
        return if (detail != null) "capture is not running: $detail" else "capture is not running"
    }

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var stateValue: ExportState
        get() = _state.value
        set(value) {
            if (value is ExportState.Error) {
                logExportError(errorLogFile, clock, "ExportEngine", value.reason.name, value.message, value.exception)
            }
            _state.value = value
        }

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
            val current = stateValue
            if (current is ExportState.Success || current is ExportState.Error) {
                stateValue = ExportState.Idle
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
            stateValue = ExportState.Exporting
        }
        var result: ExportState = ExportState.Error(
            ExportFailureReason.UNEXPECTED_FAILURE,
            "export did not complete",
        )
        val startTime = clock()
        try {
            result = runExport(durationMillis, minutesLabel, secondsLabel)
        } finally {
            if (minExportDurationMillis > 0L) {
                val elapsed = clock() - startTime
                if (elapsed < minExportDurationMillis) {
                    try {
                        Thread.sleep(minExportDurationMillis - elapsed)
                    } catch (_: InterruptedException) {}
                }
            }
            stateValue = result
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
                ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, captureNotRunningMessage())
            val oldestCursor = oldestCursorProvider()
                ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, captureNotRunningMessage())
            val rawLength = writeCursor - oldestCursor
            if (rawLength <= 0L) {
                return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, "nothing buffered yet")
            }
            val windowStart = estimateTimestampProvider(oldestCursor)
                ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, captureNotRunningMessage())

            val gaps = gapsProvider()
            // Two levels of "no segments" here, and they must not collapse to the same thing
            // (issue #332, `@rev`/`@sec` finding 2 on PR #323's last commit): no *provider* at all
            // is the legacy single-format constructor, which never had a segment record to begin
            // with -- `emptyList()` is the correct, safe input to
            // [BoundedExportPlanner.plan]'s "whole window in [targetConfig]" fallback there. A
            // provider that *returns* `null` means a segment record was expected
            // ([AudioCaptureEngine.activeSegments] does this once its ring buffer is torn down) and
            // is not there -- collapsing that into the same `emptyList()` used to make
            // [BoundedExportPlanner.plan] synthesize "the entire window was recorded in
            // targetConfig" from nothing, which is the #322 guess itself, just moved one frame
            // inward from the call sites that were fixed. Mirrors
            // `ForwardRecordingEngine.ForwardFormatReconciler.sourceAt`'s `NoRecord`/`Unresolvable`
            // split, adapted to this class's whole-window planning instead of per-cursor lookups.
            val activeSegs = if (segmentsProvider == null) {
                emptyList()
            } else {
                segmentsProvider.invoke()
                    ?: return ExportState.Error(ExportFailureReason.NO_AUDIO_BUFFERED, captureNotRunningMessage())
            }
            // The last segment wins: the file declares the format the newest buffered audio was
            // recorded in, and everything older is converted up/down into it by
            // [BoundedExportReader]. The fallback (empty list, from a legacy no-provider caller or
            // a live provider that genuinely reports none yet) is only reachable if the ring buffer
            // ever stops seeding a segment (it always does today, see RingBuffer's `segments`
            // init), and it now reads the capture config fresh rather than a copy captured at
            // construction (issue #322).
            val targetConfig = activeSegs.lastOrNull()?.config ?: configProvider?.invoke() ?: config

            // Issue #385: only a *saturated* buffer can race a slow sink/encoder open -- if
            // capture has not written a full capacity's worth of bytes yet, `oldestCursor` is
            // pinned at the session's true start and cannot advance out from under this drain
            // no matter how long the drain takes, so there is nothing to guard against and no
            // audio should be sacrificed (see startCursorMarginBytes's doc for why the margin
            // itself is sized the way it is).
            val capacityBytes = capacityBytesProvider()
            val marginBytes = if (capacityBytes != null && rawLength >= capacityBytes) {
                // Never discard the whole window: this is a defensive backstop for a
                // pathological capacity smaller than the headroom itself, not something expected
                // to trigger on any real device (the ring buffer is sized for minutes of audio).
                minOf(startCursorMarginBytes(targetConfig), maxOf(rawLength - 1L, 0L))
            } else {
                0L
            }
            val startCursor = oldestCursor + marginBytes
            val adjustedRawLength = rawLength - marginBytes
            // Re-anchor the window's wall-clock start on the actual first byte being read, not
            // the discarded margin -- otherwise the plan's gap/duration bookkeeping below would
            // measure elapsed audio time from a byte that is never read.
            //
            // `@sec` review on PR #386 (issue #385): `estimateTimestampProvider(startCursor)` can
            // only return null if capture stopped concurrently between fixing the cursor window
            // above and this call -- vanishingly rare, but falling back to the *original*
            // `windowStart` here would silently point the filename/metadata timestamp up to
            // `marginBytes` worth of time *before* the first byte this export actually reads,
            // even though `startCursor`/`adjustedRawLength` below are still the shifted ones. Fall
            // back to `windowStart` advanced by exactly the margin's own duration instead (via
            // `marginMillisFor`, the same bytesPerSecond conversion `startCursorMarginBytes` used
            // to create `marginBytes` in the first place, so the two can't drift apart) -- this
            // keeps the filename's start time consistent with the plan's actual first byte even
            // in that edge case, without special-casing millisecond-perfect accuracy the provider
            // itself couldn't answer.
            val adjustedWindowStart = if (marginBytes > 0L) {
                estimateTimestampProvider(startCursor) ?: (windowStart + marginMillisFor(marginBytes, targetConfig))
            } else {
                windowStart
            }

            val plan = BoundedExportPlanner.plan(
                startCursor = startCursor,
                rawLength = adjustedRawLength,
                windowStart = adjustedWindowStart,
                gaps = gaps,
                segments = activeSegs,
                targetConfig = targetConfig,
                targetDurationMillis = durationMillis,
            )
            val displayName = filenameFor(adjustedWindowStart, minutesLabel, secondsLabel)

            val target = try {
                sink.open(displayName, payloadEncoder.mimeType)
            } catch (e: IOException) {
                return ExportState.Error(ExportFailureReason.SINK_OPEN_FAILED, e.message ?: "sink open failed", e)
            }

            val reader = BoundedExportReader(plan, readSinceProvider, drainChunkSizeBytes)
            writeAndFinish(target, plan, reader, displayName)
        } catch (e: CancellationException) {
            throw e // preserve normal coroutine cancellation semantics, don't swallow it as a failure
        } catch (e: Throwable) {
            // Anything unexpected (a future regression in BoundedExportPlanner, a throw from
            // gapsProvider()/one of the cursor providers, ...) must still leave export() free to
            // run again on the next call, not silently stranded -- see export()'s doc comment.
            ExportState.Error(ExportFailureReason.UNEXPECTED_FAILURE, e.message ?: e.javaClass.simpleName, e)
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
                    writeFailure
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
    /**
     * Startup headroom, in bytes of [targetConfig], to add to `oldestCursor` before draining a
     * *saturated* buffer (issue #385).
     *
     * Zero headroom is what caused #385 in the field: `runExport` used to start the bounded drain
     * exactly at `oldestCursor`, so once `sink.open()` (MediaStore insert) plus the encoder's own
     * startup took any time at all, the capture writer's continuing advance evicted that exact
     * byte before the drain's very first read reached it, and the whole Save failed with
     * `CURSOR_LAPPED` -- for the loss of only a few writer batches (issue's field evidence: 7168 B
     * writer batches, worst case 21504 B / 122 ms). The same race caused #204 (measured 20-50 ms of
     * sink-open latency there).
     *
     * [STARTUP_HEADROOM_MILLIS] is a deliberate, named sacrifice of that much of the *oldest*
     * buffered audio on every Save from a saturated buffer -- not a recovery path (see #351: a
     * recovery that retried/returned partial data after a lap is exactly what produced corrupt/
     * zero-byte payloads there, and is not reintroduced by this fix). It is picked at roughly 2x
     * the worst latency actually observed in the field (122 ms) and comfortably above the #204
     * range (20-50 ms), so it survives both data points with margin to spare, while still being
     * negligible against the multi-minute retention windows this app exports.
     *
     * Expressed in time and converted through [targetConfig]'s own `bytesPerSecond` rather than a
     * fixed byte count -- a fixed byte count would silently mean a different amount of *time*
     * whenever sample rate/channel count changes (mono vs. stereo, 16 kHz vs. 44.1 kHz, ...), which
     * would defeat the whole point of sizing this against a measured latency.
     */
    private fun startCursorMarginBytes(targetConfig: AudioConfig): Long {
        val rawMarginBytes = (targetConfig.bytesPerSecond.toLong() * STARTUP_HEADROOM_MILLIS) / MILLIS_PER_SECOND
        val bytesPerFrame = targetConfig.bytesPerFrame.toLong()
        // Frame-align so the reader never starts mid-sample.
        return if (bytesPerFrame > 0L) rawMarginBytes - (rawMarginBytes % bytesPerFrame) else rawMarginBytes
    }

    /**
     * Inverse of [startCursorMarginBytes]: how much wall-clock time [marginBytes] represents in
     * [targetConfig] -- the same `bytesPerSecond` conversion, in the other direction, so the two
     * cannot independently drift apart. Used only by `adjustedWindowStart`'s fallback in
     * [runExport] (issue #385 / `@sec` review on PR #386), for the rare case
     * `estimateTimestampProvider` cannot re-derive the shifted window's start directly.
     */
    private fun marginMillisFor(marginBytes: Long, targetConfig: AudioConfig): Long =
        if (targetConfig.bytesPerSecond > 0) (marginBytes * MILLIS_PER_SECOND) / targetConfig.bytesPerSecond else 0L

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

        // See startCursorMarginBytes's doc for the full rationale (issue #385).
        const val STARTUP_HEADROOM_MILLIS = 250L

        const val MILLIS_PER_SECOND = 1000L
    }
}
