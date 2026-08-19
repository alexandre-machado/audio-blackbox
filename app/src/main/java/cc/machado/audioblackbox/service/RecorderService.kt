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
import cc.machado.audioblackbox.audio.CaptureState

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

    private var focusRequest: AudioFocusRequest? = null

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
        RecorderNotification.ensureChannel(this)
        audioManager.registerAudioRecordingCallback(recordingCallback, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android enforces a hard deadline (a handful of seconds) between the process that calls
        // Context.startForegroundService()/startService() and this service calling
        // startForeground() -- miss it and the OS kills the process and raises an ANR. That
        // deadline runs from process start, not from whenever we get around to it, so this is
        // called first, synchronously, before looking at the Intent's action or touching the
        // engine at all. It also covers the two cases with no useful action to inspect: intent ==
        // null (the OS restarting this service per the START_STICKY contract, see below) and an
        // unrecognized action -- both still need an up-to-date notification posted promptly.
        startForeground(RecorderNotification.NOTIFICATION_ID, currentNotification())

        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> {
                handleStop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SAVE -> handleSave()
            else -> Unit // null intent (OS restart) or unknown action: notification above already covers it.
        }

        // START_STICKY: if the OS kills this process to reclaim memory, recreate the service
        // (with a null Intent) rather than leaving the user silently unprotected -- the whole
        // point of this service is "the recorder keeps running unless the user explicitly stops
        // it". We deliberately do NOT use START_REDELIVER_INTENT: the start Intent carries no
        // extras whose loss would matter (ACTION_START/STOP/SAVE are each idempotent against the
        // engine's current state), so redelivering the exact same Intent buys nothing over
        // START_STICKY's null-intent restart, which already re-invokes startForeground() above.
        // Note this cannot resurrect the RAM ring buffer itself -- that is unavoidably lost on
        // process death regardless of onStartCommand's return value, since it only ever lived in
        // this process's heap; recovering from that is out of scope for this service (there is
        // nothing on disk to recover from without persistence work no module in this codebase
        // does yet).
        return START_STICKY
    }

    override fun onDestroy() {
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        abandonAudioFocus()
        // Idempotent no-op if already Idle (e.g. ACTION_STOP already called this) -- defensive
        // cleanup so AudioRecord is never left open if the service is destroyed some other way.
        engine.stop()
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
        refreshNotification()
    }

    private fun handleStop() {
        engine.stop()
        abandonAudioFocus()
    }

    private fun handleSave() {
        // TODO(#5): wire to the export engine once it exists. The notification action, the
        // PendingIntent plumbing, and this dispatch point are fully wired and exercised by this
        // module; the actual "copy the last 30 min to a .wav" logic is Module 3's responsibility
        // and does not exist in this codebase yet, so there is nothing to trigger beyond this log
        // line today.
        Log.i(TAG, "handleSave(): export engine not implemented yet (see issue #5)")
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun currentNotification() =
        RecorderNotification.build(this, engine.state.value, engine.bufferedDurationMillis())

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(RecorderNotification.NOTIFICATION_ID, currentNotification())
    }

    companion object {
        private const val TAG = "RecorderService"

        const val ACTION_START = "cc.machado.audioblackbox.service.action.START"
        const val ACTION_STOP = "cc.machado.audioblackbox.service.action.STOP"
        const val ACTION_SAVE = "cc.machado.audioblackbox.service.action.SAVE"

        // Single engine instance for the process lifetime, deliberately not scoped to a Service
        // instance: this is what makes the ring buffer survive Activity destruction (rotation,
        // recents-swipe) -- only losing the whole process kills it, which is unavoidable for a
        // RAM-only buffer regardless of where the reference lives.
        val engine = AudioCaptureEngine()

        fun startIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_STOP)

        fun saveIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_SAVE)
    }
}
