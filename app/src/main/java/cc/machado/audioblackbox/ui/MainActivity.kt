package cc.machado.audioblackbox.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.permissions.AndroidPermissionSystem
import cc.machado.audioblackbox.permissions.BatteryOptimization
import cc.machado.audioblackbox.permissions.OnboardingPreferences
import cc.machado.audioblackbox.permissions.OnboardingStep
import cc.machado.audioblackbox.permissions.PermissionResolver
import cc.machado.audioblackbox.permissions.PermissionResolverInput
import cc.machado.audioblackbox.permissions.PermissionSystem
import cc.machado.audioblackbox.permissions.SharedPrefsOnboardingPreferences
import cc.machado.audioblackbox.service.RecorderService
import cc.machado.audioblackbox.settings.DataStoreRetentionWindowPreferences
import cc.machado.audioblackbox.ui.dashboard.DashboardRoute
import cc.machado.audioblackbox.ui.dashboard.DashboardViewModel
import cc.machado.audioblackbox.ui.gallery.GalleryRoute
import cc.machado.audioblackbox.ui.onboarding.OnboardingScreen
import cc.machado.audioblackbox.ui.settings.SettingsRoute
import cc.machado.audioblackbox.ui.settings.SettingsViewModel
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var preferences: OnboardingPreferences
    private lateinit var permissionSystem: PermissionSystem

    private lateinit var recordAudioLauncher: ActivityResultLauncher<String>
    private lateinit var notificationsLauncher: ActivityResultLauncher<String>
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var batteryOptimizationLauncher: ActivityResultLauncher<Intent>

    private var stepState by mutableStateOf(OnboardingStep.DONE)
    private var recordAudioGrantedState by mutableStateOf(false)
    private var isIgnoringBatteryOptimizationsState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = SharedPrefsOnboardingPreferences(this)
        permissionSystem = AndroidPermissionSystem(this)

        recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            preferences.hasRequestedRecordAudio = true
            refreshStep()
        }
        notificationsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            preferences.hasRequestedPostNotifications = true
            refreshStep()
        }
        settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStep()
        }
        batteryOptimizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStep()
        }

        refreshStep()
        enableEdgeToEdge()
        setContent {
            AudioBlackboxTheme {
                // Issue #73, PR #74 round 2: hoisted here (not inside MainScreenWithBottomBar,
                // which no longer exists) so both Scaffold's `bottomBar` slot and its `content`
                // slot below can read/drive the same selected tab -- Scaffold requires the bar and
                // the content it insets to be siblings passed to it directly, not nested inside a
                // Box the way the previous, buggy layout had it.
                var selectedDestination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }

                // The Scaffold + bottomBar shell lives in AppScaffold (issue #78) so an
                // instrumented Compose UI test can assert its layout contract -- "content is never
                // drawn under the bar", the thing PR #74 got wrong -- without standing up
                // permissions, onboarding state, DataStore and the service. Behaviour here is
                // unchanged: same Scaffold, same bottomBar slot, same onboarding gate. Everything
                // below is the content of AppScaffold's own Column, which is where the Scaffold's
                // innerPadding is applied -- deliberately not repeated here, so there is exactly
                // one place in the codebase that can get "content clears the bar" wrong, and the
                // harness tests that place (PR #87 review).
                AppScaffold(
                    selectedDestination = selectedDestination,
                    onSelectDestination = { selectedDestination = it },
                    showBottomBar = stepState == OnboardingStep.DONE,
                ) {
                    if (stepState == OnboardingStep.DONE) {
                        val stopEngine: () -> Unit = {
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                RecorderService.stopIntent(this@MainActivity),
                            )
                        }
                        val dashboardViewModelFactory = remember {
                            viewModelFactory {
                                initializer {
                                    DashboardViewModel(
                                        onStartEngine = { startRecordingEngine() },
                                        onStopEngine = stopEngine,
                                        onSaveIntent = {
                                            ContextCompat.startForegroundService(
                                                this@MainActivity,
                                                RecorderService.saveIntent(this@MainActivity),
                                            )
                                        },
                                        onStartForwardRecording = { startFromOldest ->
                                            if (permissionSystem.recordAudioGranted()) {
                                                ContextCompat.startForegroundService(
                                                    this@MainActivity,
                                                    RecorderService.startForwardIntent(this@MainActivity, startFromOldest),
                                                )
                                            } else {
                                                refreshStep()
                                            }
                                        },
                                        onStopForwardRecording = {
                                            ContextCompat.startForegroundService(
                                                this@MainActivity,
                                                RecorderService.stopForwardIntent(this@MainActivity),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        // Issue #73: the retention control moved off the dashboard into its own
                        // Settings screen/ViewModel -- only SettingsViewModel needs
                        // retentionWindowPreferences now, DashboardViewModel no longer touches it.
                        val settingsViewModelFactory = remember {
                            viewModelFactory {
                                initializer {
                                    SettingsViewModel(
                                        onStopEngine = stopEngine,
                                        retentionWindowPreferences = DataStoreRetentionWindowPreferences(this@MainActivity),
                                    )
                                }
                            }
                        }
                        // Neither screen below needs its own contentPadding plumbing for the bar:
                        // AppScaffold's Column already reserves the space above it, in one value
                        // Scaffold computed from the bar's real measured height plus the system-bar
                        // insets.
                        if (!isIgnoringBatteryOptimizationsState) {
                            BatteryOptimizationBanner(
                                onRequestBatteryExemption = {
                                    batteryOptimizationLauncher.launch(BatteryOptimization.bestAvailableIntent(this@MainActivity))
                                },
                            )
                        }
                        when (selectedDestination) {
                            Destination.DASHBOARD -> DashboardRoute(
                                viewModel = viewModel(factory = dashboardViewModelFactory),
                            )
                            Destination.GALLERY -> GalleryRoute()
                            Destination.SETTINGS -> SettingsRoute(
                                viewModel = viewModel(factory = settingsViewModelFactory),
                            )
                        }
                    } else {
                        OnboardingScreen(
                            step = stepState,
                            onContinueLegalNotice = {
                                preferences.hasSeenLegalNotice = true
                                refreshStep()
                            },
                            onRequestRecordAudio = {
                                recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            },
                            onRequestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onOpenAppSettings = {
                                settingsLauncher.launch(appSettingsIntent())
                            },
                            onRequestBatteryExemption = {
                                // Explicitly the Activity: `this` inside AppScaffold's content slot
                                // is its ColumnScope receiver.
                                batteryOptimizationLauncher.launch(BatteryOptimization.bestAvailableIntent(this@MainActivity))
                            },
                            onSkipBatteryOptimization = {
                                preferences.hasSkippedBatteryOptimization = true
                                refreshStep()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Never trust the persisted onboarding flag as proof a permission is still granted --
        // the user can revoke RECORD_AUDIO from system Settings at any time, so the real
        // system state is re-queried on every foreground, not just on first launch.
        refreshStep()
    }

    private fun refreshStep() {
        // Queried once per refresh and kept in observable state (rather than re-read directly
        // inside the Composable) so the main screen recomposes -- e.g. the battery-optimization
        // warning disappears -- even on a resume where stepState itself doesn't change (already
        // DONE before and after). Re-querying the live system state here, not the persisted
        // "skipped" flag, is what lets a later exemption granted through system Settings (i.e.
        // never routed back through onboarding) still clear the warning.
        val recordAudioGranted = permissionSystem.recordAudioGranted()
        val isIgnoringBatteryOptimizations = permissionSystem.isIgnoringBatteryOptimizations()
        recordAudioGrantedState = recordAudioGranted
        isIgnoringBatteryOptimizationsState = isIgnoringBatteryOptimizations

        val input = PermissionResolverInput(
            recordAudioStatus = PermissionResolver.resolvePermissionStatus(
                granted = recordAudioGranted,
                shouldShowRationale = permissionSystem.shouldShowRecordAudioRationale(),
                hasRequestedBefore = preferences.hasRequestedRecordAudio,
            ),
            postNotificationsStatus = PermissionResolver.resolvePermissionStatus(
                granted = permissionSystem.postNotificationsGranted(),
                shouldShowRationale = permissionSystem.shouldShowPostNotificationsRationale(),
                hasRequestedBefore = preferences.hasRequestedPostNotifications,
            ),
            apiLevel = permissionSystem.apiLevel,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            hasSeenLegalNotice = preferences.hasSeenLegalNotice,
            hasSkippedBatteryOptimization = preferences.hasSkippedBatteryOptimization,
        )
        stepState = PermissionResolver.resolveNextStep(input)
    }

    private fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }

    /**
     * Starts [RecorderService], the source of truth for whether capture is actually running.
     * Deliberately re-queries [PermissionSystem.recordAudioGranted] here rather than trusting
     * [recordAudioGrantedState] (a Composable-recomposition snapshot that can go stale between
     * frames): the user can revoke RECORD_AUDIO from system Settings while this Activity is
     * alive, and recording must never be able to start from that stale "granted" value (see
     * issue #3 PR description / `@sec`'s PR #20 review finding). [RecorderService.handleStart]
     * re-checks the same permission again as defense in depth, but this is the check that
     * decides whether the user sees a route back into onboarding instead of a silently-ignored
     * tap.
     */
    private fun startRecordingEngine() {
        if (permissionSystem.recordAudioGranted()) {
            ContextCompat.startForegroundService(this, RecorderService.startIntent(this))
        } else {
            refreshStep()
        }
    }
}

/**
 * Persistent warning shown above the dashboard for a user who skipped the battery-optimization
 * exemption (the app must stay usable, per issue #4, but the risk of the OS killing background
 * recording must stay visible). The caller gates this on the live PowerManager state, not the
 * stored "skipped" flag, so it disappears on its own if the exemption is later granted from
 * system Settings.
 */
@Composable
fun BatteryOptimizationBanner(onRequestBatteryExemption: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(id = R.string.onboarding_battery_warning))
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onRequestBatteryExemption) {
                    Text(text = stringResource(id = R.string.onboarding_battery_grant))
                }
            }
        }
    }
}
