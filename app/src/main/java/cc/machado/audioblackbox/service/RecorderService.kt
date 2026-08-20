package cc.machado.audioblackbox.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import cc.machado.audioblackbox.audio.AudioCaptureEngine
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportEngine
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.MediaStoreSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private lateinit var focusTracker: AudioFocusTracker

    // Service-scoped: collects engine.state reactively (see onCreate) so the notification can
    // never go stale between the two user-initiated call sites, and hosts the off-main-thread
    // engine.stop() dispatch (see stopServiceCompletely). SupervisorJob so a failure in one
    // launched child (there is normally only ever one at a time) cannot cancel the scope itself.
    // Cancelled in onDestroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Built lazily (not in the companion, unlike `engine`) because it needs a Context
    // (MediaStoreSink -> ContentResolver), which is only available once this Service instance is
    // attached. Reads `engine.snapshot()`/`engine.gaps.value` at export time via method
    // references, so it always sees whatever session is current -- not a stale one captured here.
    private val exportEngine by lazy {
        ExportEngine(
            config = captureConfig,
            snapshotProvider = engine::snapshot,
            gapsProvider = { engine.gaps.value },
            sink = MediaStoreSink(applicationContext),
        )
    }

    // Ignoring intent: see class doc on the AudioFocus criterion of issue #3 -- losing audio
    // focus must not stop the service. This listener's only job is to prove that a focus-change
    // callback firing never crashes and never triggers a stop; it deliberately does nothing else.
    // Only the AudioRecordingCallback below (matched to our own session) drives pause()/resume().
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "onAudioFocusChange($focusChange) -- ignored, capture continues")
    }

    // Matches AudioRecordingConfiguration.clientAudioSessionId against engine.audioSessionId so
    // this only reacts to *our* session being silenced, not some unrelated app's. isClientSilenced
    // requires API 30 (R); below that there is no framework signal for "the mic was taken by a
    // higher-priority client" short of polling read() starvation, so on API 29 this callback is a
    // documented no-op and interruption pause/resume simply does not fire automatically. minSdk is
    // 29 for other reasons (see AudioConfig/build.gradle.kts); this is a known, accepted gap on
    // that one OS version rather than something unit-testable or silently pretended away.
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val sessionId = engine.audioSessionId ?: return
            val ourConfig = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
            if (ourConfig.isClientSilenced) engine.pause() else engine.resume()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusTracker = AudioFocusTracker(audioManager)
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
            engine.state.collect { refreshNotification() }
        }
        // Same reactive pattern, applied to ExportEngine's own StateFlow (PR #28 review,
        // `@sec`/`@techlead` finding 4): Exporting/Success/Error each need to reach the
        // notification the instant they happen, not just Logcat -- see handleSave() and
        // RecorderNotification.build's exportState parameter.
        serviceScope.launch {
            exportEngine.state.collect { refreshNotification() }
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
            ACTION_START -> handleStart()
            ACTION_STOP -> stopServiceCompletely()
            ACTION_SAVE -> handleSave()
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
        focusTracker.abandon()
        // Defensive cleanup for the case where this service is destroyed some other way than
        // through stopServiceCompletely() (e.g. the OS force-destroys it directly). In the common
        // case stopServiceCompletely() has already driven engine.stop() to completion before ever
        // calling stopSelf(), so this is an idempotent no-op. It is dispatched on its own daemon
        // thread rather than blocked on synchronously, for the same ANR reason as
        // stopServiceCompletely(): the join + RingBuffer.clear() must not run on this callback's
        // main thread. A raw Thread (not serviceScope, which is cancelled right below, and would
        // cancel this along with it) so it survives both that cancellation and this Service
        // instance being destroyed -- engine's own capture thread is likewise independent of both.
        Thread({ engine.stop() }, "RecorderService-onDestroy-stop").apply {
            isDaemon = true
            start()
        }
        serviceScope.cancel()
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
        requestAudioFocus()
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
        serviceScope.launch(Dispatchers.Default) {
            engine.stop()
            withContext(Dispatchers.Main.immediate) {
                focusTracker.abandon()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleSave() {
        // Dispatched off this Service's main thread for the same ANR reason as
        // stopServiceCompletely(): ExportEngine.export() is blocking I/O (ring buffer copy-out is
        // already bounded/off-thread by the time it gets here, but the WAV encode + MediaStore
        // write are real disk/IPC work). engine.snapshot()/engine.gaps.value are read from this
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
            val bufferMinutes = captureConfig.bufferDurationMinutes
            val result = exportEngine.export(
                durationMillis = bufferMinutes.toLong() * MILLIS_PER_MINUTE,
                minutesLabel = bufferMinutes,
            )
            when (result) {
                is ExportState.Success ->
                    Log.i(TAG, "handleSave(): wrote ${result.displayName} (${result.bytesWritten} bytes)")
                is ExportState.Error ->
                    Log.w(TAG, "handleSave(): export failed (${result.reason}): ${result.message}")
                else -> Unit
            }
        }
    }

    private fun requestAudioFocus() {
        // focusTracker.request() abandons any request it already holds before installing this
        // one, so a repeated ACTION_START (duplicate tap, redelivered intent) can never leak a
        // prior registration with AudioManager (PR #23 review, `@rev` finding 4).
        focusTracker.request {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build()
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
        }
    }

    private fun currentNotification() = RecorderNotification.build(
        this,
        engine.state.value,
        engine.bufferedDurationMillis(),
        exportEngine.state.value,
    )

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(RecorderNotification.NOTIFICATION_ID, currentNotification())
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val MILLIS_PER_MINUTE = 60_000L

        const val ACTION_START = "cc.machado.audioblackbox.service.action.START"
        const val ACTION_STOP = "cc.machado.audioblackbox.service.action.STOP"
        const val ACTION_SAVE = "cc.machado.audioblackbox.service.action.SAVE"

        // Shared with the exportEngine instance property (see above) so the WAV header
        // ExportEngine writes always matches the config the ring buffer was actually captured
        // at -- never a hardcoded/independent copy.
        private val captureConfig = AudioConfig()

        // Single engine instance for the process lifetime, deliberately not scoped to a Service
        // instance: this is what makes the ring buffer survive Activity destruction (rotation,
        // recents-swipe) -- only losing the whole process kills it, which is unavoidable for a
        // RAM-only buffer regardless of where the reference lives.
        val engine = AudioCaptureEngine(config = captureConfig)

        fun startIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_STOP)

        fun saveIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_SAVE)
    }
}
