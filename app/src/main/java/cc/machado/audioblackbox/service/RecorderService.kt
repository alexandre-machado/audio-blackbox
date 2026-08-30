package cc.machado.audioblackbox.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import cc.machado.audioblackbox.audio.AudioCaptureEngine
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.export.AacPayloadEncoder
import cc.machado.audioblackbox.export.ExportEngine
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ForwardRecordingEngine
import cc.machado.audioblackbox.export.ForwardRecordingState
import cc.machado.audioblackbox.export.StreamingAacWriter
import cc.machado.audioblackbox.export.MediaStoreSink
import cc.machado.audioblackbox.PreloadedRetentionWindow
import cc.machado.audioblackbox.settings.DataStoreRecordingPreferences
import cc.machado.audioblackbox.settings.RecordingPreferences
import cc.machado.audioblackbox.settings.isValidRetentionMinutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground microphone service (issue #3). Owns the single [AudioCaptureEngine] instance for
 * the process, so the ring buffer survives Activity destruction (rotation, app swiped from
 * recents) as long as this service -- and therefore the process -- is alive: the engine lives in
 * [Companion], not inside any Activity.
 *
 * ## Starting
 * Must be started via [startIntent] from a visible, user-initiated context (a button tap while
 * the app is in the foreground) -- Android forbids starting a foreground service from the
 * background on API 26+, and [MainActivity][cc.machado.audioblackbox.ui.MainActivity] re-checks
 * `RECORD_AUDIO` at the moment of the tap before doing so (never trusting a cached "granted"
 * value -- see issue #3 PR description).
 *
 * ## onStartCommand contract
 * [startForeground] is called *first*, unconditionally, before this method looks at the
 * Intent's action at all -- see the comment on [onStartCommand] for why.
 */
class RecorderService : Service() {

    private lateinit var audioManager: AudioManager

    // Service-scoped: collects engine.state reactively (see onCreate) so the notification can
    // never go stale between the two user-initiated call sites, and hosts the off-main-thread
    // engine.stop() dispatch (see stopServiceCompletely). SupervisorJob so a failure in one
    // launched child (there is normally only ever one at a time) cannot cancel the scope itself.
    // Cancelled in onDestroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // See PeriodicNotificationRefresher's doc: supplies the "keep refreshing while Recording
    // doesn't transition" tick the collector below cannot, since captureState only emits on a
    // transition and buffered duration keeps changing without one (issue #30). Reads the
    // companion's forwarded `captureState` -- not `engine.state` directly -- so this keeps working
    // across a retention-window rebuild (issue #45), which replaces the underlying `engine`
    // instance wholesale (see `attachEngineForwarding`'s doc).
    private val recordingPreferences: RecordingPreferences by lazy {
        DataStoreRecordingPreferences(applicationContext)
    }

    private val notificationRefresher = PeriodicNotificationRefresher(captureState)
    private var isGracefullyStopping = false

    // Built lazily (not in the companion, unlike `engine`) because it needs a Context
    // (MediaStoreSink -> ContentResolver; AacPayloadEncoder -> cacheDir for its temp file, see its
    // class doc), which is only available once this Service instance is attached.
    //
    // readSinceProvider/writeCursorProvider/oldestCursorProvider/estimateTimestampProvider/
    // gapsProvider are lambdas that read the companion's `engine` property fresh on every call,
    // deliberately not bound method references (`engine::readSince`) captured once at this lazy
    // block's first evaluation (issue #45): a retention-window change can replace `engine`
    // wholesale for the lifetime of this same Service instance (see `rebuildEngineIfIdle`), and a
    // bound reference would keep exporting from the old, abandoned engine forever after that.
    // `config` does not need the same treatment -- sampleRateHz/encoding/channelCount never
    // change across a rebuild, only bufferDurationMinutes, which ExportEngine never reads (see its
    // own field, private and unused for that purpose).
    //
    // AacPayloadEncoder (`.m4a`, issue #32) is the production default, not WavPayloadEncoder --
    // see issue #32's device evidence for why (176 audio files on the target device, zero WAV).
    // WavPayloadEncoder stays available for a future user-facing lossless setting.
    private val exportEngine by lazy {
        ExportEngine(
            config = captureConfig,
            readSinceProvider = { cursor, maxBytes -> engine.readSince(cursor, maxBytes) },
            writeCursorProvider = { engine.writeCursor() },
            oldestCursorProvider = { engine.oldestCursor() },
            estimateTimestampProvider = { offset -> engine.estimateTimestamp(offset) },
            gapsProvider = { engine.gaps.value },
            sink = MediaStoreSink(applicationContext),
            payloadEncoder = AacPayloadEncoder(tempDir = applicationContext.cacheDir),
            segmentsProvider = { engine.activeSegments() ?: emptyList() },
        )
    }

    private val forwardRecordingEngine by lazy {
        ForwardRecordingEngine(
            configProvider = { captureConfig },
            readSinceProvider = { cursor, maxBytes -> engine.readSince(cursor, maxBytes) },
            writeCursorProvider = { engine.writeCursor() },
            oldestCursorProvider = { engine.oldestCursor() },
            gapsProvider = { engine.gaps.value },
            sink = MediaStoreSink(applicationContext),
            writerFactory = { target, cfg -> StreamingAacWriter(target, cfg) },
        )
    }

    // Matches AudioRecordingConfiguration.clientAudioSessionId against engine.audioSessionId so
    // this only reacts to *our* session being silenced, not some unrelated app's. isClientSilenced
    // requires API 30 (R); below that there is no framework signal for "the mic was taken by a
    // higher-priority client" short of polling read() starvation, so on API 29 this callback is a
    // documented no-op and interruption pause/resume simply does not fire automatically. minSdk is
    // 29 for other reasons (see AudioConfig/build.gradle.kts); this is a known, accepted gap on
    // that one OS version rather than something unit-testable or silently pretended away.
    //
    // The decision itself lives in MicrophoneSilencing.decide, not here (issue #155): an anonymous
    // callback taking a framework Parcelable is unreachable from a JVM test, which is how the
    // strand bug this replaces survived unnoticed.
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            applySilencingDecision(configs.toActiveCaptures())
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        RecorderNotification.ensureChannel(this)
        audioManager.registerAudioRecordingCallback(recordingCallback, null)
        // Reactive notification refresh (PR #23 review, `@sec` finding 1 / `@rev` finding 2):
        // nothing about a single explicit refresh call site can catch every way engine.state
        // changes asynchronously -- a read error on the capture thread, the mic being silenced by
        // recordingCallback above, or a permission revoked mid-session. Collecting the StateFlow
        // itself, instead of only refreshing from onStartCommand/handleStart(), covers the general
        // case: any state transition, from anywhere, posts an up-to-date notification. A
        // StateFlow replays its latest value to a new collector, so this also immediately
        // reconciles the notification with whatever engine.state already is at the moment this
        // collector attaches.
        serviceScope.launch {
            captureState.collect {
                refreshNotification()
            }
        }
        // Periodic tick, alongside (not instead of) the transition-driven collector above --
        // see PeriodicNotificationRefresher's class doc for cadence/cost/lifecycle reasoning
        // (issue #30).
        serviceScope.launch {
            notificationRefresher.run { refreshNotification() }
        }
        // Watchdog for the silencing strand (issue #155). Deliberately not folded into
        // PeriodicNotificationRefresher: that one only ticks while Recording (see its
        // collectLatest), which is exactly the state we are *not* in when stranded -- it would
        // never run in the only situation this exists for. Ticks solely while Paused, so a
        // healthy recording session pays nothing for it, and stops on its own the moment the
        // reconcile below succeeds in resuming.
        serviceScope.launch {
            captureState.collectLatest { current ->
                if (current is CaptureState.Paused) {
                    while (true) {
                        delay(SILENCING_RECHECK_INTERVAL_MILLIS)
                        reconcileSilencing()
                    }
                }
            }
        }
        // Same reactive pattern, applied to ExportEngine's own StateFlow (PR #28 review,
        // `@sec`/`@techlead` finding 4): Exporting/Success/Error each need to reach the
        // notification the instant they happen, not just Logcat -- see handleSave() and
        // RecorderNotification.build's exportState parameter.
        //
        // Success/Error are terminal but not permanent: after this refresh has actually shown the
        // outcome, exportEngine.acknowledgeTerminalState() resets it back to Idle so a later,
        // unrelated notification refresh (e.g. a phone-call pause hours after a save) can't keep
        // reasserting a stale export outcome forever (PR #28 review round 2, `@sec`/`@rev`
        // finding; `@techlead` round-3 adjudication item 2). The delay is deliberate, not
        // incidental: resetting immediately after refreshNotification() would race the very next
        // refresh -- exportEngine.state is a StateFlow, which conflates rapid updates for a slow
        // collector, so an immediate Idle write here could mean the user never actually sees
        // "Exported: ..."/"Falha ao salvar" at all. EXPORT_OUTCOME_VISIBLE_MILLIS is long enough
        // for a glance at the notification shade; acknowledgeTerminalState() is itself a no-op if
        // a newer export has since started (state would be Exporting, not the acknowledged
        // Success/Error), so this can't stomp on a later call.
        // Also forwarded into the companion's `_exportState` (issue #40 item 2 -- `@design`/`@rev`
        // finding on issue #6): unlike `engine.state`, `exportEngine` used to be a private,
        // per-Service-instance property, so nothing outside this Service could ever observe a real
        // Exporting/Success/Error transition -- the dashboard's "saving" state meant only "intent
        // sent". Forwarding here (rather than exposing this instance's `exportEngine` directly)
        // keeps `ExportEngine`'s own Context-dependent construction exactly as it was (still lazy,
        // still per-instance) while still giving external observers a StateFlow that survives this
        // Service instance being recreated, matching `engine.state`'s companion-object visibility.
        serviceScope.launch {
            exportEngine.state.collect { state ->
                _exportState.value = state
                refreshNotification()
                if (state is ExportState.Success || state is ExportState.Error) {
                    delay(EXPORT_OUTCOME_VISIBLE_MILLIS)
                    exportEngine.acknowledgeTerminalState()
                }
            }
        }
        serviceScope.launch {
            forwardRecordingEngine.state.collect { state ->
                _forwardRecordingState.value = state
                refreshNotification()
                if (state is ForwardRecordingState.Success || state is ForwardRecordingState.Error) {
                    delay(EXPORT_OUTCOME_VISIBLE_MILLIS)
                    forwardRecordingEngine.acknowledgeTerminalState()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android enforces a hard deadline (a handful of seconds) between the process that calls
        // Context.startForegroundService()/startService() and this service calling
        // startForeground() -- miss it and the OS kills the process and raises an ANR. That
        // deadline runs from process start, not from whenever we get around to it, so this is
        // called first, synchronously, before looking at the Intent's action or touching the
        // engine at all. It also covers the two cases with no useful action to inspect: intent ==
        // null (an OS-initiated restart, see below) and an unrecognized action -- both still need
        // an up-to-date notification posted promptly.
        startForeground(RecorderNotification.NOTIFICATION_ID, currentNotification())

        when (intent?.action) {
            ACTION_START -> {
                serviceScope.launch(Dispatchers.IO) {
                    recordingPreferences.setRecordingDesired(true)
                }
                handleStart()
            }
            ACTION_STOP -> {
                serviceScope.launch(Dispatchers.IO) {
                    recordingPreferences.setRecordingDesired(false)
                }
                stopServiceCompletely()
            }
            // Issue #121: one Save action, always "everything currently buffered" -- both the
            // dashboard's and the notification's own ACTION_SAVE dispatch the exact same bare
            // Intent now, so there is no extra left to read here.
            ACTION_SAVE -> handleSave()
            ACTION_START_FORWARD -> handleStartForward()
            ACTION_STOP_FORWARD -> handleStopForward()
            null -> stopServiceCompletely() // OS-initiated restart: see START_NOT_STICKY note below.
            else -> Unit // unrecognized action: notification above already covers it, nothing to do.
        }

        // START_NOT_STICKY (PR #23 review, `@techlead` adjudication -- overrules this service's
        // original START_STICKY choice). An OS-initiated restart after this process was killed to
        // reclaim memory would deliver a null Intent here, and START_STICKY's contract would have
        // this service settle into a permanent idle foreground state (or, in an earlier draft,
        // silently resume capture) -- both wrong for this product:
        //   1. There is nothing to restore: the ring buffer is RAM-only, so an auto-restart cannot
        //      recover a single second of the audio that died with the process. It would only ever
        //      start a *new* capture, not resume the old one.
        //   2. An OS-initiated restart is a background start. Silently reopening the microphone
        //      from the background is restricted on API 34+ (a `microphone`-typed FGS cannot be
        //      started from the background there) and is the wrong default regardless -- an
        //      ambient-audio recorder must never reopen the mic without a user action that
        //      triggered it.
        //   3. A zombie idle foreground service with a notification the user never asked to see
        //      again, and that nothing ever tears down, is worse than not restarting at all.
        // So instead: null-Intent delivery tears the service down immediately (see
        // stopServiceCompletely()) rather than idling forever. Consequence, stated plainly: after
        // a process death, capture stays stopped until the user starts it again -- that is honest,
        // not a regression, since there was never anything to actually resume.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        // Defensive cleanup for the case where this service is destroyed some other way than
        // through stopServiceCompletely() (e.g. the OS force-destroys it directly). In the common
        // case stopServiceCompletely() has already driven engine.stop() to completion before ever
        // calling stopSelf(), so running engine.stop() asynchronously here is not only redundant
        // but would race a subsequent startIntent() by stopping the next session asynchronously.
        if (!isGracefullyStopping) {
            Thread({
                forwardRecordingEngine.stop()
                engine.stop()
            }, "RecorderService-onDestroy-stop").apply {
                isDaemon = true
                start()
            }
        }
        serviceScope.cancel()
        _isServiceRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStart() {
        // Re-checked here too, in addition to MainActivity's own re-check right before it builds
        // this Intent: defense in depth against any other caller of startIntent() in the future,
        // and against the (tiny but real) window between the tap and this method running.
        // AudioCaptureEngine.start() is documented to assume the caller already holds
        // RECORD_AUDIO and does not check it itself, so skipping this check here would let a
        // revoked-permission session crash instead of failing safely.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "handleStart(): RECORD_AUDIO not granted, refusing to start capture")
            return
        }
        engine.start()
        // No explicit refreshNotification() call here: the serviceScope collector registered in
        // onCreate() reacts to the engine.state emission engine.start() just produced. Relying on
        // that single reactive path (rather than this call site plus the collector) is the point
        // of the fix -- see onCreate()'s comment.
    }

    /** Shared teardown for both an explicit [ACTION_STOP] and a null-Intent OS restart (see
     * [onStartCommand]'s `START_NOT_STICKY` note) -- both want the same thing: stop capture, then
     * remove the notification and the service.
     *
     * `engine.stop()` blocks until the capture thread's cleanup -- including [RingBuffer.clear],
     * an `Arrays.fill` over the whole ring buffer -- has fully run, which is small at the default
     * retention window but can be seconds at a long one. Dispatched on [serviceScope] with
     * [Dispatchers.Default] so that join never happens on this Service's main thread (PR #23
     * review, `@rev` finding 3), then hops back to Main to remove the notification and stop the
     * service only once capture has actually finished -- never before, so the persistent
     * notification (and the service itself) never disappear while the mic might still be open.
     */
    private fun stopServiceCompletely() {
        isGracefullyStopping = true
        serviceScope.launch(Dispatchers.Default) {
            forwardRecordingEngine.stop()
            engine.stop()
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleStartForward() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "handleStartForward(): RECORD_AUDIO not granted, refusing to start")
            return
        }
        if (engine.state.value is CaptureState.Idle) {
            handleStart()
        }
        serviceScope.launch(Dispatchers.Default) {
            forwardRecordingEngine.start()
        }
    }

    private fun handleStopForward() {
        serviceScope.launch(Dispatchers.Default) {
            forwardRecordingEngine.stop()
        }
    }

    /** Issue #121: the single Save action always requests the full configured capacity from
     * [ExportEngine.export] -- [ExportEngine]/[cc.machado.audioblackbox.audio.RingBuffer.snapshot]
     * clamp that down to whatever is genuinely buffered if the buffer has not filled yet, so this
     * never has to duplicate that clamp. What it must not do is *label* the resulting file with
     * the requested capacity when less was actually buffered -- see [resolveSavedMinutes]'s doc
     * for why the label is computed from what's actually buffered at dispatch time instead. */
    private fun handleSave() {
        val requestedMinutes = captureConfig.bufferDurationMinutes
        val bufferedMillis = engine.bufferedDurationMillis() ?: 0L
        val savedMinutes = resolveSavedMinutes(bufferedMillis, requestedMinutes)
        // Sub-minute save (issue #129 follow-up): a floored `0min` filename never overstates, but
        // for an evidentiary product it is useless for telling a 45s clip apart from an empty one
        // later. Only computed/used when savedMinutes is genuinely 0 -- see resolveSavedSeconds's
        // doc and ExportEngine.filenameFor's secondsLabel parameter.
        val savedSeconds = if (savedMinutes == 0) resolveSavedSeconds(bufferedMillis) else null
        // Dispatched off this Service's main thread for the same ANR reason as
        // stopServiceCompletely(): ExportEngine.export() is blocking I/O (ring buffer copy-out is
        // already bounded/off-thread by the time it gets here, but the WAV encode + MediaStore
        // write are real disk/IPC work). engine.readSince()/engine.gaps.value are read from this
        // background thread; both are documented safe to call from any thread (see
        // AudioCaptureEngine's field docs).
        //
        // No explicit refreshNotification() call here, same reasoning as handleStart(): the
        // serviceScope collector on exportEngine.state registered in onCreate() reacts to every
        // Exporting/Success/Error transition export() below produces, including the
        // EXPORT_ALREADY_IN_PROGRESS rejection ExportEngine itself now returns for a concurrent
        // call (double-tap on the notification's Save action, or an OS-redelivered Intent) --
        // that rejection still surfaces to the user as an Error state, it just isn't logged twice.
        serviceScope.launch(Dispatchers.Default) {
            val result = exportEngine.export(
                durationMillis = requestedMinutes.toLong() * MILLIS_PER_MINUTE,
                minutesLabel = savedMinutes,
                secondsLabel = savedSeconds,
            )
            when (result) {
                is ExportState.Success -> {
                    Log.i(TAG, "handleSave(): wrote ${result.displayName} (${result.bytesWritten} bytes)")
                }
                is ExportState.Error -> {
                    Log.w(TAG, "handleSave(): export failed (${result.reason}): ${result.message}")
                }
                else -> Unit
            }
        }
    }

    private fun currentNotification() = RecorderNotification.build(
        this,
        engine.state.value,
        engine.bufferedDurationMillis(),
        exportEngine.state.value,
        capacityMinutes = captureConfig.bufferDurationMinutes,
        forwardRecordingState = forwardRecordingEngine.state.value,
        bytesPerSecond = captureConfig.bytesPerSecond,
    )

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(RecorderNotification.NOTIFICATION_ID, currentNotification())
    }

    /** Maps the framework's configuration list onto the one field the decision needs, so
     * [MicrophoneSilencing.decide] never has to see a `Parcelable` it cannot be tested against. */
    private fun List<AudioRecordingConfiguration>.toActiveCaptures(): List<ActiveCapture> =
        map { ActiveCapture(sessionId = it.clientAudioSessionId, isSilenced = it.isClientSilenced) }

    private fun applySilencingDecision(active: List<ActiveCapture>) {
        when (MicrophoneSilencing.decide(engine.audioSessionId, active)) {
            SilencingDecision.PAUSE -> engine.pause()
            SilencingDecision.RESUME -> engine.resume()
            SilencingDecision.NOT_CAPTURING -> Unit
        }
    }

    /**
     * Re-derives the pause/resume decision from the framework's *current* state rather than
     * waiting for another `onRecordingConfigChanged` (issue #155).
     *
     * The callback above is edge-triggered: it only runs when some recording configuration
     * changes. Every recovery from a silenced state therefore depends on an event that may simply
     * never arrive -- and because being wrongly stuck in Paused means the ring buffer silently
     * holds nothing while the notification still reads as running, "we believe the event always
     * arrives" is not a strong enough basis for the one failure mode that destroys the product.
     * This is the level-triggered counterpart: it asks the framework what is true right now.
     */
    private fun reconcileSilencing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        applySilencingDecision(audioManager.activeRecordingConfigurations.toActiveCaptures())
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val MILLIS_PER_MINUTE = 60_000L

        // How long a Save outcome (Success/Error) stays reflected in the persistent notification
        // before exportEngine's state is reset to Idle -- see the onCreate() collector comment.
        private const val EXPORT_OUTCOME_VISIBLE_MILLIS = 8_000L

        // How often, while Paused, to re-ask the framework whether we are still silenced
        // (issue #155). Only ever runs in the Paused state, so this is not a background poll on a
        // healthy session. Short enough that a missed un-silencing event costs seconds of audio
        // rather than the whole session, long enough that a genuine interruption (a phone call)
        // is not re-queried dozens of times for no benefit.
        private const val SILENCING_RECHECK_INTERVAL_MILLIS = 5_000L

        const val ACTION_START = "cc.machado.audioblackbox.service.action.START"
        const val ACTION_STOP = "cc.machado.audioblackbox.service.action.STOP"
        const val ACTION_SAVE = "cc.machado.audioblackbox.service.action.SAVE"
        const val ACTION_START_FORWARD = "cc.machado.audioblackbox.service.action.START_FORWARD"
        const val ACTION_STOP_FORWARD = "cc.machado.audioblackbox.service.action.STOP_FORWARD"

        /**
         * The minute label a Save should carry -- for the exported file's name ([handleSave]) and
         * for the notification's own Save action label ([cc.machado.audioblackbox.service.RecorderNotification.build])
         * -- computed from what is *actually* buffered, never from [capacityMinutes] (issue #121).
         * Before the buffer fills, [bufferedMillis] is the smaller number; labeling either surface
         * with the full configured capacity instead would promise more than the save actually
         * delivers, exactly the dishonesty issue #121 exists to close. Floors to whole minutes
         * (matching the old chip selector's "only X min available" convention) and is clamped to
         * [capacityMinutes] as a defensive upper bound -- a reading momentarily above capacity
         * (e.g. a stale poll racing a capacity shrink) must never be reported as more than what was
         * actually requested.
         */
        fun resolveSavedMinutes(bufferedMillis: Long, capacityMinutes: Int): Int =
            (bufferedMillis / MILLIS_PER_MINUTE).toInt().coerceIn(0, capacityMinutes)

        /**
         * The whole-seconds label for a sub-minute save (issue #129 follow-up): both
         * [handleSave]'s exported filename and [cc.machado.audioblackbox.service.RecorderNotification.saveActionLabel]
         * call this -- never re-derive it independently -- so the two surfaces can't drift apart
         * on this narrower case the same way [resolveSavedMinutes] already guarantees for the
         * whole-minutes case. Only meaningful (and only ever called) when [resolveSavedMinutes]
         * itself resolved to `0`; floors to whole seconds and clamps to `0..59` so it can never
         * read `60s` (which would just be the `1min` case misrepresented) nor overstate a
         * momentarily stale reading.
         */
        fun resolveSavedSeconds(bufferedMillis: Long): Int =
            (bufferedMillis / 1000L).coerceIn(0L, 59L).toInt()

        // Built from PreloadedRetentionWindow.minutes (issue #45), not the bare
        // AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES constant: AudioBlackboxApplication.onCreate
        // always runs before anything can touch this companion object, so by the time either
        // `captureConfig` or `engine` below is first referenced, PreloadedRetentionWindow already
        // holds whatever was actually persisted (or the constant, on first run / a fresh install --
        // see PreloadedRetentionWindow's own doc for why that fallback is safe).
        //
        // `var`, not `val`, and mutated only by `rebuildEngineIfIdle` below -- shared with the
        // exportEngine instance property (see above) so the payload encoder always matches the
        // config the ring buffer was actually captured at, never a hardcoded/independent copy.
        //
        // `@Volatile` (`@techlead` adjudication on PR #57, item 2): the single writer today is
        // `rebuildEngineIfIdle`, but readers are not single -- `RecorderService.captureConfig`/
        // `.engine` are read from `DashboardViewModel` on the main thread and from this service's
        // own coroutines on `Dispatchers.Default`. Without `@Volatile` a non-synchronized `var`
        // gives no cross-thread visibility guarantee under the JVM memory model, so a reader on
        // another thread could keep observing the pre-rebuild reference indefinitely -- the same
        // family of bug as issue #30 (a frozen notification invisible across four review rounds).
        // This is defensive, not a fix for an observed bug: it costs nothing at runtime and closes
        // that class of doubt for the price of one keyword. Not unit-testable (JMM visibility is
        // not something a single-JVM test can observe either way), so no test is attached to this.
        @Volatile
        private var _captureConfig = PreloadedRetentionWindow.preset.config(
            bufferDurationMinutes = PreloadedRetentionWindow.minutes,
        )
        val captureConfig: AudioConfig get() = _captureConfig

        // Single engine instance for the process lifetime, deliberately not scoped to a Service
        // instance: this is what makes the ring buffer survive Activity destruction (rotation,
        // recents-swipe) -- only losing the whole process kills it, which is unavoidable for a
        // RAM-only buffer regardless of where the reference lives.
        //
        // `var`, not `val` (issue #45): a retention-window change cannot resize the ring buffer in
        // place (see AudioConfig's class doc -- it is pre-allocated once and never grows), so
        // changing it means constructing a brand new AudioCaptureEngine and replacing this
        // reference wholesale. See `rebuildEngineIfIdle` for the one place that is allowed to
        // happen, and `attachEngineForwarding`/`captureState` for how every other reader of this
        // engine's state stays correct across that replacement instead of latching onto a
        // reference that is about to go stale.
        //
        // `@Volatile` -- same reasoning as `_captureConfig` above (`@techlead` adjudication on
        // PR #57, item 2): `engine` is read from multiple threads, and only the writer
        // (`rebuildEngineIfIdle`) being single does not give readers on other threads a visibility
        // guarantee over a plain `var`.
        @Volatile
        private var _engine = AudioCaptureEngine(config = _captureConfig)
        val engine: AudioCaptureEngine get() = _engine

        // Forwarded mirror of whatever `_engine.state` currently is (issue #45), re-subscribed by
        // `attachEngineForwarding` every time `_engine` is replaced. Every caller that used to read
        // `engine.state` directly (RecorderService's own onCreate() collector,
        // PeriodicNotificationRefresher, DashboardViewModel) reads this instead, specifically so a
        // retention-window rebuild mid-process cannot leave any of them permanently watching an
        // abandoned engine's StateFlow -- a bound reference captured once (the way `engine.state`
        // used to be handed out) would never see another transition once `_engine` moves on.
        private val _captureState = MutableStateFlow<CaptureState>(_engine.state.value)
        val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

        // Forwarded the same way and for the same reason as `captureState` above: a
        // retention-window rebuild replaces `_engine` wholesale, and a collector bound directly to
        // the old engine's `inputLevel` would sit at its last value forever after that -- a frozen
        // meter, which is precisely the failure this level was made real to avoid.
        private val _inputLevel = MutableStateFlow(0f)
        val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

        private var engineForwardingJob: Job? = null
        private var levelForwardingJob: Job? = null

        // Companion-owned, process-lifetime scope for the forwarding job above -- mirrors
        // `engine`'s own "lives in Companion, not inside any Activity/Service instance" lifetime
        // (see class doc) rather than any one Service instance's `serviceScope`, which is cancelled
        // on that instance's onDestroy() while `engine`/`captureState` must keep working across
        // Service instance recreation.
        //
        // Dispatchers.Unconfined, not Dispatchers.Main.immediate: this companion object is touched
        // by plain JVM unit tests (no Android `Looper`/`Dispatchers.setMain` available) as well as
        // the real app process, and forwarding a value from one `MutableStateFlow` into another has
        // no main-thread-affinity requirement -- `MutableStateFlow.value`'s setter is thread-safe on
        // its own. Unconfined just means "run the forwarding on whatever thread the source engine's
        // state actually changed on", which is exactly what happens anyway.
        private val forwardingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        init {
            attachEngineForwarding(_engine)
        }

        private fun attachEngineForwarding(newEngine: AudioCaptureEngine) {
            engineForwardingJob?.cancel()
            engineForwardingJob = forwardingScope.launch {
                newEngine.state.collect { _captureState.value = it }
            }
            levelForwardingJob?.cancel()
            _inputLevel.value = 0f
            levelForwardingJob = forwardingScope.launch {
                newEngine.inputLevel.collect { _inputLevel.value = it }
            }
        }

        // Reactive mirror of captureConfig.bufferDurationMinutes (issue #45, extending issue #40
        // item 3's `bufferDurationMinutes` below): DashboardViewModel's retention/capacity display
        // needs to react to a rebuild without needing its own ViewModel recreated, the same reason
        // `captureState` exists above instead of a plain `engine.state` reference.
        private val _bufferDurationMinutesFlow = MutableStateFlow(_captureConfig.bufferDurationMinutes)
        val bufferDurationMinutesFlow: StateFlow<Int> = _bufferDurationMinutesFlow.asStateFlow()

        private val _qualityPresetFlow = MutableStateFlow(PreloadedRetentionWindow.preset)
        val qualityPresetFlow: StateFlow<QualityPreset> = _qualityPresetFlow.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        // Public mirror of captureConfig.bufferDurationMinutes (issue #40 item 3 -- `@rev` finding
        // on issue #6): DashboardViewModel used to default its own capacityMinutes to the bare
        // AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES constant, which happens to match this today
        // but nothing kept the two in sync structurally. Exposing the real value here, the same
        // way `engine` itself is exposed, means the dashboard's denominator can never drift from
        // whatever capacity this service is actually running with. Kept as a plain `Int` getter
        // (alongside `bufferDurationMinutesFlow` above) for callers that only need "the current
        // value right now" and do not want to collect a Flow for it (e.g. `saveIntent`'s default
        // parameter below).
        val bufferDurationMinutes: Int get() = _captureConfig.bufferDurationMinutes

        val qualityPreset: QualityPreset get() = _qualityPresetFlow.value

        // Published mirror of the current Service instance's exportEngine.state (issue #40 item 2
        // -- see the onCreate() collector that forwards into this). Starts at Idle, same as a
        // freshly constructed ExportEngine would, so a dashboard observing this before the service
        // has ever been created sees exactly what it would see after -- no export has happened.
        private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

        private val _forwardRecordingState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        val forwardRecordingState: StateFlow<ForwardRecordingState> = _forwardRecordingState.asStateFlow()

        fun startIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_STOP)

        fun startForwardIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java)
                .setAction(ACTION_START_FORWARD)

        fun stopForwardIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_STOP_FORWARD)

        /** Issue #121 retired the dashboard's 5/15/30-minute window selector: `ACTION_SAVE` no
         * longer carries a requested window at all -- every Save, from the dashboard or the
         * notification, means exactly the same thing:
         * "export everything currently buffered". [handleSave] still requests the full configured
         * capacity from [cc.machado.audioblackbox.export.ExportEngine]; that engine clamps it down
         * to what is genuinely buffered if the buffer has not filled yet (see
         * [cc.machado.audioblackbox.audio.RingBuffer.snapshot]), and [resolveSavedMinutes] labels
         * the resulting file honestly rather than with the requested capacity. */
        fun saveIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_SAVE)

        /**
         * Switches retention duration and/or quality preset dynamically (issues #194, #223).
         * If the engine is recording or paused, dynamically resizes the buffer and/or switches
         * the capture format seamlessly without discarding surviving buffered audio.
         */
        fun switchSettings(
            newBufferDurationMinutes: Int = _captureConfig.bufferDurationMinutes,
            newPreset: QualityPreset = _qualityPresetFlow.value,
        ) {
            require(isValidRetentionMinutes(newBufferDurationMinutes)) {
                "newBufferDurationMinutes must be in " +
                    "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..${AudioConfig.RETENTION_WINDOW_MAX_MINUTES} " +
                    "and a multiple of ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}, was $newBufferDurationMinutes"
            }
            val newConfig = newPreset.config(bufferDurationMinutes = newBufferDurationMinutes)
            _captureConfig = newConfig
            _bufferDurationMinutesFlow.value = newBufferDurationMinutes
            _qualityPresetFlow.value = newPreset
            _engine.switchConfig(newConfig)
        }

        /**
         * Switches the active quality preset dynamically (issue #194).
         * If the engine is recording, switches the capture format seamlessly without discarding buffered audio.
         */
        fun switchQualityPreset(newPreset: QualityPreset) {
            switchSettings(newBufferDurationMinutes = _captureConfig.bufferDurationMinutes, newPreset = newPreset)
        }

        /**
         * Switches the retention window duration dynamically (issue #223).
         * If the engine is recording, resizes the buffer in-place without discarding surviving buffered audio.
         */
        fun switchRetentionMinutes(newBufferDurationMinutes: Int) {
            switchSettings(newBufferDurationMinutes = newBufferDurationMinutes, newPreset = _qualityPresetFlow.value)
        }

        /**
         * Rebuilds the process-lifetime engine at [newBufferDurationMinutes] and [newPreset] (issue #45, #193).
         * Returns `false` and changes nothing if [captureState] is not currently [CaptureState.Idle] --
         * this is the enforcement point for "never silently discard buffered audio because the
         * user opened a settings screen": [CaptureState.Recording]/[CaptureState.Paused] both mean
         * real audio is sitting in the ring buffer right now, and rebuilding would discard it with
         * no warning. Callers (see [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s
         * retention-window flow) are expected to stop the engine and confirm with the user first
         * when it is not already Idle, then call this once it is.
         *
         * Safe to call while genuinely Idle: [AudioCaptureEngine.stop] already tears the ring
         * buffer down to nothing before a session reaches Idle (see its class doc on
         * `RingBuffer.clear`), so there is nothing left to lose at that point -- this only ever
         * discards a buffer that is already empty.
         */
        fun rebuildEngineIfIdle(
            newBufferDurationMinutes: Int = _captureConfig.bufferDurationMinutes,
            newPreset: QualityPreset = _qualityPresetFlow.value,
        ): Boolean {
            require(isValidRetentionMinutes(newBufferDurationMinutes)) {
                "newBufferDurationMinutes must be in " +
                    "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..${AudioConfig.RETENTION_WINDOW_MAX_MINUTES} " +
                    "and a multiple of ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}, was $newBufferDurationMinutes"
            }
            if (_captureState.value !is CaptureState.Idle) return false
            val newConfig = newPreset.config(bufferDurationMinutes = newBufferDurationMinutes)
            _captureConfig = newConfig
            _engine = AudioCaptureEngine(config = newConfig)
            attachEngineForwarding(_engine)
            _bufferDurationMinutesFlow.value = newBufferDurationMinutes
            _qualityPresetFlow.value = newPreset
            return true
        }
    }
}
