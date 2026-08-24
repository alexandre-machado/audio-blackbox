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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
                Scaffold { innerPadding ->
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
                                        onSaveIntent = { minutes ->
                                            ContextCompat.startForegroundService(
                                                this@MainActivity,
                                                RecorderService.saveIntent(this@MainActivity, minutes),
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
                        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            if (!isIgnoringBatteryOptimizationsState) {
                                BatteryOptimizationBanner(
                                    onRequestBatteryExemption = {
                                        batteryOptimizationLauncher.launch(BatteryOptimization.bestAvailableIntent(this@MainActivity))
                                    },
                                )
                            }
                            MainScreenWithBottomBar(
                                dashboardViewModel = viewModel(factory = dashboardViewModelFactory),
                                settingsViewModel = viewModel(factory = settingsViewModelFactory),
                            )
                        }
                    } else {
                        OnboardingScreen(
                            step = stepState,
                            modifier = Modifier.padding(innerPadding),
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
                                batteryOptimizationLauncher.launch(BatteryOptimization.bestAvailableIntent(this))
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
 * Hosts [Destination.DASHBOARD]/[Destination.SETTINGS] behind a [FloatingBottomBar] (issue #73).
 * Hoisted `enum` + `remember`ed selected-tab state, not `navigation-compose` (see issue #73's
 * explicit instruction and [FloatingBottomBar]'s own doc) -- two destinations do not justify that
 * dependency.
 *
 * The bar's real, measured height (not a guessed constant) is fed back into each screen's
 * [DashboardRoute]/[SettingsRoute] `contentPadding` so their own scrollable `Column`s add enough
 * bottom padding to scroll their last item clear of the floating bar instead of it being clipped or
 * hidden underneath -- issue #73's "the bar must not cover content" requirement. This container
 * itself does not add its own system-bar inset handling: it lives inside [MainActivity]'s outer
 * [Scaffold], whose `innerPadding` this whole subtree is already placed within, so the floating bar
 * only needs a fixed visual margin from that already-safe edge, not a second insets consumption.
 */
@Composable
fun MainScreenWithBottomBar(
    dashboardViewModel: DashboardViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedDestination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val barMargin = 16.dp
    val contentPadding = PaddingValues(bottom = barHeight + barMargin)

    Box(modifier = modifier.fillMaxSize()) {
        when (selectedDestination) {
            Destination.DASHBOARD -> DashboardRoute(viewModel = dashboardViewModel, contentPadding = contentPadding)
            Destination.SETTINGS -> SettingsRoute(viewModel = settingsViewModel, contentPadding = contentPadding)
        }
        FloatingBottomBar(
            selected = selectedDestination,
            onSelect = { selectedDestination = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(barMargin)
                .onSizeChanged { size -> barHeight = with(density) { size.height.toDp() } },
        )
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
