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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.permissions.AndroidPermissionSystem
import cc.machado.audioblackbox.permissions.BatteryOptimization
import cc.machado.audioblackbox.permissions.OnboardingPreferences
import cc.machado.audioblackbox.permissions.OnboardingStep
import cc.machado.audioblackbox.permissions.PermissionResolver
import cc.machado.audioblackbox.permissions.PermissionResolverInput
import cc.machado.audioblackbox.permissions.PermissionSystem
import cc.machado.audioblackbox.permissions.SharedPrefsOnboardingPreferences
import cc.machado.audioblackbox.ui.onboarding.OnboardingScreen

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
            MaterialTheme {
                Scaffold { innerPadding ->
                    if (stepState == OnboardingStep.DONE) {
                        MainScreen(
                            modifier = Modifier.padding(innerPadding),
                            recordAudioGranted = recordAudioGrantedState,
                            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizationsState,
                            onRequestBatteryExemption = {
                                batteryOptimizationLauncher.launch(BatteryOptimization.bestAvailableIntent(this))
                            },
                        )
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
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    recordAudioGranted: Boolean = true,
    isIgnoringBatteryOptimizations: Boolean = true,
    onRequestBatteryExemption: () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = stringResource(id = R.string.app_name))
                Column(modifier = Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = {}, enabled = recordAudioGranted) {
                        Text(text = stringResource(id = R.string.main_start_engine))
                    }
                    if (!recordAudioGranted) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(text = stringResource(id = R.string.main_start_engine_disabled_reason))
                        }
                    }
                }
                // Visible, persistent warning for a user who skipped the battery-optimization
                // exemption (the app must stay usable, per issue #4, but the risk of the OS
                // killing background recording must stay visible). Gated on the live
                // PowerManager state, not the stored "skipped" flag, so it disappears on its
                // own if the exemption is later granted from system Settings.
                if (!isIgnoringBatteryOptimizations) {
                    Column(
                        modifier = Modifier.padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = stringResource(id = R.string.onboarding_battery_warning))
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = onRequestBatteryExemption) {
                                Text(text = stringResource(id = R.string.onboarding_battery_grant))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen()
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenBatteryWarningPreview() {
    MaterialTheme {
        MainScreen(isIgnoringBatteryOptimizations = false)
    }
}
