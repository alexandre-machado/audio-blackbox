package cc.machado.audioblackbox.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.settings.DataStoreRecordingPreferences
import cc.machado.audioblackbox.settings.RecordingPreferences
import cc.machado.audioblackbox.ui.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Quick Settings Tile service for 1-tap toggling of continuous background recording (issue #79).
 *
 * Reflects [Tile.STATE_ACTIVE] when recording or paused, and [Tile.STATE_INACTIVE] when idle or in error,
 * accompanied by user-visible state subtitles.
 */
class AudioBlackboxTileService : TileService() {

    private var activeServiceScope: CoroutineScope? = null
    private var stateCollectionJob: Job? = null

    override fun onStartListening() {
        val scope = tileScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        activeServiceScope = scope
        stateCollectionJob = scope.launch {
            captureStateFlowProvider().collect { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        if (tileScope == null) {
            activeServiceScope?.cancel()
        }
        activeServiceScope = null
    }

    override fun onClick() {
        val context: Context = try {
            applicationContext ?: this
        } catch (_: Throwable) {
            this
        }

        if (!permissionChecker(context, Manifest.permission.RECORD_AUDIO)) {
            launchMainActivityForPermission()
            return
        }

        val currentState = captureStateFlowProvider().value
        val preferences = recordingPreferencesFactory(context)
        when (currentState) {
            is CaptureState.Recording, is CaptureState.Paused -> {
                // Issue #267: this write must survive onStopListening() collapsing the tile's
                // binding (a click on a QS tile collapses the shade immediately, which unbinds
                // the tile before this suspend function has a chance to commit). It is launched
                // on preferencesScope -- an application-scoped coroutine scope whose lifetime is
                // the process, not the tile binding -- specifically so onStopListening() cannot
                // cancel it out from under the click that started it.
                preferencesScope.launch {
                    preferences.setRecordingDesired(false)
                }
                serviceStarter(context, intentFactory(context, RecorderService.ACTION_STOP))
            }
            is CaptureState.Idle, is CaptureState.Error -> {
                // Same reasoning as the stop branch above, kept symmetric even though the bug is
                // invisible here today (recordingDesired and the observed outcome happen to
                // agree on the start path) -- see issue #267.
                preferencesScope.launch {
                    preferences.setRecordingDesired(true)
                }
                serviceStarter(context, intentFactory(context, RecorderService.ACTION_START))
            }
        }
    }

    private fun updateTileState(captureState: CaptureState) {
        val tile = try {
            qsTile
        } catch (_: Throwable) {
            null
        } ?: return
        val (state, subtitleResId) = mapTileState(captureState)
        tile.state = state
        tile.subtitle = getString(subtitleResId)
        tile.updateTile()
    }

    private fun launchMainActivityForPermission() {
        activityLauncher(this, activityIntentFactory(this))
    }

    companion object {
        fun mapTileState(captureState: CaptureState): Pair<Int, Int> = when (captureState) {
            is CaptureState.Recording -> Tile.STATE_ACTIVE to R.string.tile_state_recording
            is CaptureState.Paused -> Tile.STATE_ACTIVE to R.string.tile_state_paused
            is CaptureState.Idle -> Tile.STATE_INACTIVE to R.string.tile_state_idle
            is CaptureState.Error -> Tile.STATE_INACTIVE to R.string.tile_state_error
        }

        @SuppressLint("StartActivityAndCollapseDeprecated")
        fun defaultActivityLauncher(service: TileService, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    service,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                service.startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                service.startActivityAndCollapse(intent)
            }
        }

        // A failed setRecordingDesired() write (e.g. a DataStore IOException) must not crash the
        // process: this scope is process-lifetime, not scoped to "this tile click", so an
        // uncaught exception here would otherwise propagate to the platform's default
        // uncaught-exception handler. Logging and swallowing is the right tradeoff -- there is no
        // UI surface to report a failed background preference write to, and the write itself is
        // a best-effort durability improvement, not a correctness-critical operation the rest of
        // the click depends on (issue #271, `@rev`/`@sec` findings).
        private val preferencesScopeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Failed to persist recordingDesired", throwable)
        }

        // Application-scoped: intentionally never bound to a tile instance's or a tile
        // binding's lifetime, so onStopListening() unbinding the tile can never cancel a write
        // launched from onClick() (issue #267). Overridable in tests for deterministic control.
        //
        // Ordering note (issue #271, `@sec` finding 2): two `preferencesScope.launch { }` calls
        // from consecutive taps are independent coroutines with no ordering guarantee relative to
        // each other on `Dispatchers.IO`'s thread pool. A sufficiently fast stop-then-start
        // double-tap could in principle persist the earlier tap's value if the writes complete
        // out of order. Explicitly accepted, not fixed: this requires the user's own rapid manual
        // re-tapping (not attacker-controlled or reachable from any external input), the resting
        // state is quickly re-corrected by the next real interaction with the tile or app, and
        // introducing a serializing queue for this specific write is disproportionate complexity
        // for a benign, self-correcting, user-only race. Revisit if a future consumer of
        // `recordingDesired` needs strict linearizability.
        var preferencesScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + preferencesScopeExceptionHandler)

        // Injectable seams for unit testing
        var tileScope: CoroutineScope? = null
        var captureStateFlowProvider: () -> StateFlow<CaptureState> = { RecorderService.captureState }
        var permissionChecker: (Context, String) -> Boolean = { ctx, perm ->
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }
        var recordingPreferencesFactory: (Context) -> RecordingPreferences = { DataStoreRecordingPreferences(it) }
        var serviceStarter: (Context, Intent) -> Unit = { ctx, intent ->
            ContextCompat.startForegroundService(ctx, intent)
        }
        var intentFactory: (Context, String) -> Intent = { ctx, action ->
            Intent(ctx, RecorderService::class.java).setAction(action)
        }
        var activityIntentFactory: (Context) -> Intent = { ctx ->
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
        var activityLauncher: (TileService, Intent) -> Unit = ::defaultActivityLauncher

        fun resetTestOverrides() {
            preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + preferencesScopeExceptionHandler)
            tileScope = null
            captureStateFlowProvider = { RecorderService.captureState }
            permissionChecker = { ctx, perm ->
                ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
            }
            recordingPreferencesFactory = { DataStoreRecordingPreferences(it) }
            serviceStarter = { ctx, intent ->
                ContextCompat.startForegroundService(ctx, intent)
            }
            intentFactory = { ctx, action ->
                Intent(ctx, RecorderService::class.java).setAction(action)
            }
            activityIntentFactory = { ctx ->
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            }
            activityLauncher = ::defaultActivityLauncher
        }
    }
}

private const val TAG = "AudioBlackboxTileService"
