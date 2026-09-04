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

/**
 * A completed [AudioCaptureEngine.pause]/[AudioCaptureEngine.resume] cycle, recorded as
 * wall-clock boundaries rather than a duration alone so a consumer (Module 3's export gap
 * handler) can place the injected silence at the right offset in the exported timeline, not
 * just size it correctly. Exposed as data on [AudioCaptureEngine.gaps] -- deliberately not
 * something the foreground service tracks privately, since the engine is the only thing that
 * knows exactly when writes stopped/resumed being written into the ring buffer.
 */
data class PauseGap(val startTimestampMillis: Long, val endTimestampMillis: Long) {
    val durationMillis: Long get() = endTimestampMillis - startTimestampMillis
}

/** Outcome of [AudioCaptureEngine.switchConfig] (issue #272). */
sealed interface SwitchConfigResult {
    /** The new config took effect: `activeConfig` updated, and the live buffer resized (if any). */
    data object Applied : SwitchConfigResult

    /** Refused before touching anything -- see [AudioCaptureEngine.switchConfig]'s doc. */
    data class BufferResizeRefused(val outcome: ResizeOutcome.Refused) : SwitchConfigResult
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

    /** The OS refused to promote the hosting service to a foreground service (a
     * `SecurityException` from `Service.startForeground`, e.g. a while-in-use eligibility gate
     * this start attempt did not satisfy -- see issue #267/#275) *before* [AudioCaptureEngine.start]
     * ever ran. Reported via [AudioCaptureEngine.reportForegroundPromotionRefused], not raised
     * from inside this class. */
    FOREGROUND_SERVICE_PROMOTION_REFUSED,
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
    // Issue #322: the same kind of seam `audioRecordFactory` already is, for the *other* framework
    // call this class makes. `AudioRecord.getMinBufferSize` is static, and Mockito's static mocks
    // are thread-local -- so a JVM test could only ever mock it for calls made on the test thread,
    // which left the capture thread's own format-swap path (`captureLoop`, the one that decides
    // whether `buffer.setFormat` may advance) structurally untestable at Tier 0. That is exactly
    // how the mislabelling this issue is about went unnoticed. Routing the lookup through a
    // function makes both call sites reachable from a test on any thread.
    private val minBufferSizeProvider: (AudioConfig) -> Int = ::defaultMinBufferSize,
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    // Completed pause/resume cycles for the *current* session, oldest first. Reset to empty by
    // start() so a new session never carries a stale gap from a previous one forward (see
    // start()). Written only inside `synchronized(lock)`, from pause()/resume()/start().
    private val _gaps = MutableStateFlow<List<PauseGap>>(emptyList())
    val gaps: StateFlow<List<PauseGap>> = _gaps.asStateFlow()

    // Live microphone input level in 0f..1f, recomputed from the PCM of every read() (see
    // captureLoop). Written only from the capture thread; MutableStateFlow's setter is thread-safe
    // and conflates for a slow collector, so the UI reads whatever the most recent block measured
    // without the capture loop ever blocking on it.
    //
    // Forced back to 0f whenever audio is not reaching the ring buffer -- paused, stopped, or
    // errored. That is the honesty requirement this exists for: the meter must read empty exactly
    // when nothing is being captured, including while another app holds the microphone.
    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

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

    // Wall-clock start of the pause currently in progress, set by pause() and consumed by
    // resume() to close out a PauseGap. Only meaningful while `paused` is true; only ever
    // written/read inside `synchronized(lock)`.
    private var pauseStartMillis = 0L

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

    private val pendingConfigSwitch = java.util.concurrent.atomic.AtomicReference<AudioConfig?>(null)

    /** Active capture configuration (may be updated across presets without resetting buffer, issue #194). */
    var activeConfig: AudioConfig = config
        private set

    /** The buffer the capture thread is writing into, or `null` before the first [start] or
     * after [stop] (see [RingBuffer.clear] -- capture is not considered stopped until the raw
     * PCM is unreachable through this method). */
    fun snapshot(durationMillis: Long): AudioSnapshot? = ringBuffer?.snapshot(durationMillis)

    /** Stream offset of the write head, or `null` before the first [start] or after [stop]. */
    fun writeCursor(): Long? = ringBuffer?.writeCursor()

    /** Stream offset of the oldest byte still buffered, or `null` before the first [start] or after [stop]. */
    fun oldestCursor(): Long? = ringBuffer?.oldestCursor()

    /**
     * Incremental drain read from the live buffer, or `null` before the first [start] or after [stop].
     */
    fun readSince(cursor: Long, maxBytes: Int): ReadSinceResult? = ringBuffer?.readSince(cursor, maxBytes)

    /** Wall-clock estimate for [streamOffset], or `null` before the first [start] or after [stop].
     * See [RingBuffer.estimateTimestamp] -- backs the bounded "save the past" export path (issue
     * #72), which needs a window's wall-clock start without a full [snapshot]. */
    fun estimateTimestamp(streamOffset: Long): Long? = ringBuffer?.estimateTimestamp(streamOffset)

    /** Active format segments covering the range `[startCursor, endCursor)` (issue #194). */
    fun activeSegments(startCursor: Long? = null, endCursor: Long? = null): List<FormatSegment>? {
        val buf = ringBuffer ?: return null
        val start = startCursor ?: buf.oldestCursor()
        val end = endCursor ?: buf.writeCursor()
        return buf.activeSegments(start, end)
    }

    /** Format active when [streamOffset] was written (issue #194). */
    fun formatAt(streamOffset: Long): AudioConfig? = ringBuffer?.formatAt(streamOffset)

    /**
     * Switches the capture configuration dynamically (issues #194, #223). If recording,
     * resizes the ring buffer in-place (preserving buffered audio) and switches AudioRecord
     * seamlessly on the capture thread if the audio format changed.
     *
     * ## Refusal is all-or-nothing (issue #272)
     * If a live ring buffer's [RingBuffer.resize] refuses (would not fit given the current heap),
     * nothing about this call takes effect: [activeConfig] is left exactly as it was and no
     * config switch is queued for the capture thread. The caller gets that back as
     * [SwitchConfigResult.BufferResizeRefused] instead of a crash, and must surface it -- a
     * refused resize is not silent, per "never fake a signal in the UI" (AGENTS.md §5). Recording
     * (or paused) capture keeps running unaffected at its previous capacity; no buffered audio is
     * discarded either way.
     */
    fun switchConfig(newConfig: AudioConfig, memoryBudget: MemoryBudget = MemoryBudget.REAL): SwitchConfigResult {
        synchronized(lock) {
            val newCapacityBytes = newConfig.totalBufferBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val buffer = ringBuffer
            if (buffer != null) {
                val outcome = try {
                    buffer.resize(newCapacityBytes, memoryBudget)
                } catch (oom: OutOfMemoryError) {
                    // Defense in depth: the pre-flight check above should make this unreachable in
                    // practice, but a resize must never be allowed to crash the process even if the
                    // budget estimate turns out to be wrong on some device.
                    return SwitchConfigResult.BufferResizeRefused(
                        ResizeOutcome.Refused(newCapacityBytes, Long.MAX_VALUE, Long.MAX_VALUE),
                    )
                }
                if (outcome is ResizeOutcome.Refused) {
                    return SwitchConfigResult.BufferResizeRefused(outcome)
                }
            }
            activeConfig = newConfig
            if (_state.value is CaptureState.Recording || _state.value is CaptureState.Paused) {
                pendingConfigSwitch.set(newConfig)
            }
            return SwitchConfigResult.Applied
        }
    }

    /** Whether capture is currently running and the buffer is accessible. */
    fun isRunning(): Boolean = ringBuffer != null && (_state.value is CaptureState.Recording || _state.value is CaptureState.Paused)

    /** How much audio the ring buffer currently holds, in milliseconds, or `null` before the
     * first [start] / after [stop]. Used by the foreground service's notification to show
     * elapsed buffered duration (issue #3) -- derived from the live multi-format buffer. */
    fun bufferedDurationMillis(): Long? = ringBuffer?.bufferedDurationMillis()

    /** The current session's `AudioRecord.getAudioSessionId()`, or `null` when not
     * Recording/Paused. Lets the foreground service match this engine's session against
     * `AudioManager.AudioRecordingCallback`'s `AudioRecordingConfiguration` list to detect when
     * *this* capture (as opposed to some unrelated app's) has been silenced by a higher-priority
     * client. */
    val audioSessionId: Int? get() = synchronized(lock) { audioRecord?.audioSessionId }

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

            val currentCfg = activeConfig
            val capacityBytes = currentCfg.totalBufferBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val buffer = try {
                RingBuffer(capacityBytes = capacityBytes, initialConfig = currentCfg, clock = clock)
            } catch (oom: OutOfMemoryError) {
                _state.value = CaptureState.Error(
                    CaptureErrorReason.BUFFER_ALLOCATION_FAILED,
                    "Failed to allocate $capacityBytes-byte ring buffer: ${oom.message}",
                )
                return
            }

            val minBufferSize = minBufferSizeProvider(currentCfg)
            if (minBufferSize <= 0) {
                _state.value = CaptureState.Error(
                    CaptureErrorReason.UNSUPPORTED_CONFIG,
                    "AudioRecord.getMinBufferSize returned $minBufferSize for $currentCfg",
                )
                return
            }

            // `currentCfg` (== activeConfig), never the constructor's `config` (issue #322).
            // A preset change that reaches this engine while it is not Recording/Paused moves
            // `activeConfig` only -- there is no capture thread to hand a pending swap to -- so the
            // two diverge, and the ring buffer above is already stamped `currentCfg`. Opening
            // AudioRecord at `config` here therefore wrote PCM in one format under a label
            // declaring another: export saw source == target, converted nothing, and declared the
            // wrong rate for real audio. 16 kHz mono PCM declared 44.1 kHz stereo plays 5.5x fast
            // and unintelligible, with a duration that still looks plausible in the container --
            // silent corruption of the one artifact this product exists to produce.
            val record = try {
                audioRecordFactory(currentCfg, minBufferSize)
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
            _inputLevel.value = 0f
            _gaps.value = emptyList()
            generation += 1
            val myGeneration = generation
            _state.value = CaptureState.Recording

            val thread = Thread(
                // `currentCfg` again, for the same reason (issue #322): the loop's notion of "the
                // format the open AudioRecord is delivering" must start as the format this session
                // actually opened it with, or the first pending swap compares against the wrong
                // baseline -- a swap to the format already running would be skipped as a no-op
                // while the buffer stayed mislabelled.
                { captureLoop(record, buffer, minBufferSize, myGeneration, currentCfg) },
                "AudioCaptureEngine",
            )
            thread.isDaemon = true
            captureThread = thread
            thread.start()
        }
    }

    /**
     * Forces [state] into [CaptureState.Error] for a failure that happened entirely outside this
     * engine and before [start] could ever run -- specifically, [RecorderService.onStartCommand]
     * catching the OS refusing to promote the hosting service to a foreground service. This is
     * the seam that lets that refusal become a real, observable [CaptureState.Error] instead of
     * an uncaught `SecurityException` killing the process (issue #267/#275; PR #278 review,
     * `@rev` finding 1).
     *
     * No-ops if a session is already [CaptureState.Recording] or [CaptureState.Paused]: an
     * external caller reporting a *start* refusal must never stomp on capture already known to be
     * genuinely running. (The spike behind this feature verified that re-promoting an
     * already-running foreground service is not re-checked against the eligibility gate, so a
     * refusal should never actually reach this method while a session is live -- this guard is
     * defense in depth, mirroring [start]'s own no-op guard, not a fix for an observed collision.)
     *
     * Deliberately does not touch `ringBuffer`/`audioRecord`/`captureThread`: nothing was ever
     * opened, since this always fires before [start] runs, so there is nothing to release.
     */
    fun reportForegroundPromotionRefused(reason: CaptureErrorReason, message: String) {
        synchronized(lock) {
            when (_state.value) {
                is CaptureState.Recording, is CaptureState.Paused -> return
                else -> _state.value = CaptureState.Error(reason, message)
            }
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
     * call interruption). No-op unless currently [CaptureState.Recording]. Records the wall-clock
     * start of the gap; see [resume] for where it is closed out. */
    fun pause() {
        synchronized(lock) {
            if (_state.value is CaptureState.Recording) {
                paused = true
                pauseStartMillis = clock()
                // The capture loop stops measuring while paused, so without this the meter would
                // freeze at the last level it saw instead of dropping to empty -- the exact
                // "looks alive while it is not" failure this meter was rewritten to end.
                _inputLevel.value = 0f
                _state.value = CaptureState.Paused
            }
        }
    }

    /** Resumes writes into the ring buffer. No-op unless currently [CaptureState.Paused].
     * Appends a [PauseGap] spanning [pauseStartMillis] to now onto [gaps] -- the wall-clock
     * duration the export gap handler (Module 3) needs to inject the right amount of silence.
     * Also prunes [gaps] of any entry that has aged out of the retention window -- see
     * [pruneExpiredGaps]. */
    fun resume() {
        synchronized(lock) {
            if (_state.value is CaptureState.Paused) {
                paused = false
                val now = clock()
                _gaps.value = pruneExpiredGaps(_gaps.value + PauseGap(pauseStartMillis, now), now)
                _state.value = CaptureState.Recording
            }
        }
    }

    /**
     * Drops any [PauseGap] that has scrolled entirely out of the ring buffer's retention window.
     * Not an arbitrary cap: the ring buffer only ever holds the most recent
     * [AudioConfig.bufferDurationMinutes] of audio, so once a gap's end is older than that window,
     * the audio surrounding it no longer exists in the buffer either -- the export gap handler
     * (Module 3) can never need a gap it has nothing left to place, because there is nothing left
     * to export around it. Pruning on this basis keeps [gaps] bounded by the same window the
     * audio itself is bounded by, for a session with arbitrarily many pause/resume cycles (e.g.
     * repeated mic contention) over an arbitrarily long run. Must be called with [lock] held.
     */
    private fun pruneExpiredGaps(gaps: List<PauseGap>, now: Long): List<PauseGap> {
        // activeConfig, not the constructor `config` (issue #322, same family as start()'s bug):
        // a retention-window change moves `bufferDurationMinutes` on activeConfig, so pruning
        // against `config` measured gaps against whatever window this engine happened to be
        // constructed with -- dropping still-relevant gaps after the window grew, or keeping dead
        // ones after it shrank. Not audio corruption, but the gap list feeds export's silence
        // placement, so it is the same "one source of truth for the live format" rule.
        val retentionMillis = activeConfig.bufferDurationMinutes.toLong() * MILLIS_PER_MINUTE
        val cutoff = now - retentionMillis
        return gaps.filter { it.endTimestampMillis > cutoff }
    }

    private fun captureLoop(
        record: AudioRecord,
        buffer: RingBuffer,
        minBufferSize: Int,
        myGeneration: Long,
        sessionConfig: AudioConfig,
    ) {
        var currentRecord = record
        var currentConfig = sessionConfig
        var scratch = ByteArray(minBufferSize)
        try {
            while (!stopRequested) {
                val targetConfig = pendingConfigSwitch.getAndSet(null)
                if (targetConfig != null && targetConfig != currentConfig) {
                    val formatChanged = targetConfig.sampleRateHz != currentConfig.sampleRateHz ||
                        targetConfig.channelCount != currentConfig.channelCount
                    if (formatChanged) {
                        // Deliberately inside the same guard as the swap itself: if this lookup
                        // fails, or the swap below fails, `buffer.setFormat` must NOT run --
                        // the buffer's label may only advance once AudioRecord is genuinely
                        // delivering the new format (issue #322). Keeping the old label with the
                        // old data is correct; advancing the label alone is the corruption.
                        val newMinBufferSize = try {
                            minBufferSizeProvider(targetConfig)
                        } catch (_: Exception) {
                            0
                        }
                        if (newMinBufferSize > 0) {
                            try {
                                val newRecord = audioRecordFactory(targetConfig, newMinBufferSize)
                                if (newRecord.state == AudioRecord.STATE_INITIALIZED) {
                                    newRecord.startRecording()
                                    if (newRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                        try {
                                            currentRecord.stop()
                                        } catch (_: Exception) {}
                                        currentRecord.release()
                                        currentRecord = newRecord
                                        synchronized(lock) {
                                            audioRecord = newRecord
                                        }
                                        scratch = ByteArray(newMinBufferSize)
                                        buffer.setFormat(targetConfig)
                                        currentConfig = targetConfig
                                    } else {
                                        newRecord.release()
                                    }
                                } else {
                                    newRecord.release()
                                }
                            } catch (_: Exception) {
                                // Fallback: keep currentRecord
                            }
                        }
                    } else {
                        currentConfig = targetConfig
                    }
                }

                val bytesRead = currentRecord.read(scratch, 0, scratch.size)
                if (bytesRead > 0) {
                    if (!paused) {
                        buffer.write(scratch, 0, bytesRead)
                        _inputLevel.value = AudioLevel.peakLevel(scratch, 0, bytesRead)
                    }
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
            _inputLevel.value = 0f
            synchronized(lock) {
                try {
                    currentRecord.stop()
                } catch (_: IllegalStateException) {
                    // Already stopped/uninitialized; release() below still runs.
                }
                currentRecord.release()
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
        const val MILLIS_PER_SECOND = 1000L
        const val MILLIS_PER_MINUTE = 60_000L

        fun mapReadError(code: Int): CaptureErrorReason = when (code) {
            AudioRecord.ERROR_INVALID_OPERATION -> CaptureErrorReason.READ_INVALID_OPERATION
            AudioRecord.ERROR_BAD_VALUE -> CaptureErrorReason.READ_BAD_VALUE
            AudioRecord.ERROR_DEAD_OBJECT -> CaptureErrorReason.READ_DEAD_OBJECT
            else -> CaptureErrorReason.READ_UNKNOWN_ERROR
        }

        /** Production [minBufferSizeProvider]: the real framework lookup for [config]'s format. */
        fun defaultMinBufferSize(config: AudioConfig): Int = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            channelConfigFor(config.channelCount),
            AudioFormat.ENCODING_PCM_16BIT,
        )

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
