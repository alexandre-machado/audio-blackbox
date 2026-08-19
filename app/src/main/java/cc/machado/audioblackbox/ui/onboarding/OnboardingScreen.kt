package cc.machado.audioblackbox.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.permissions.OnboardingStep

/**
 * Renders the onboarding step handed to it by [PermissionResolver.resolveNextStep]. This
 * composable itself holds no state and makes no Android permission/settings calls directly --
 * every action is a callback supplied by the caller (the Activity), which is the only layer
 * allowed to touch the real permission/battery APIs.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    modifier: Modifier = Modifier,
    onContinueLegalNotice: () -> Unit = {},
    onRequestRecordAudio: () -> Unit = {},
    onRequestNotifications: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onRequestBatteryExemption: () -> Unit = {},
    onSkipBatteryOptimization: () -> Unit = {},
) {
    when (step) {
        OnboardingStep.LEGAL_NOTICE -> OnboardingStepContent(
            modifier = modifier,
            title = stringResource(R.string.onboarding_legal_title),
            body = stringResource(R.string.onboarding_legal_body),
            primaryLabel = stringResource(R.string.onboarding_legal_continue),
            onPrimaryClick = onContinueLegalNotice,
        )

        OnboardingStep.AUDIO_RATIONALE -> OnboardingStepContent(
            modifier = modifier,
            title = stringResource(R.string.onboarding_audio_title),
            body = stringResource(R.string.onboarding_audio_body),
            primaryLabel = stringResource(R.string.onboarding_audio_grant),
            onPrimaryClick = onRequestRecordAudio,
        )

        OnboardingStep.AUDIO_PERMANENTLY_DENIED -> OnboardingStepContent(
            modifier = modifier,
            title = stringResource(R.string.onboarding_audio_denied_title),
            body = stringResource(R.string.onboarding_audio_denied_body),
            primaryLabel = stringResource(R.string.onboarding_open_settings),
            onPrimaryClick = onOpenAppSettings,
        )

        OnboardingStep.NOTIFICATIONS_RATIONALE -> OnboardingStepContent(
            modifier = modifier,
            title = stringResource(R.string.onboarding_notifications_title),
            body = stringResource(R.string.onboarding_notifications_body),
            primaryLabel = stringResource(R.string.onboarding_notifications_grant),
            onPrimaryClick = onRequestNotifications,
        )

        OnboardingStep.NOTIFICATIONS_PERMANENTLY_DENIED -> OnboardingStepContent(
            modifier = modifier,
            title = stringResource(R.string.onboarding_notifications_denied_title),
            body = stringResource(R.string.onboarding_notifications_denied_body),
            primaryLabel = stringResource(R.string.onboarding_open_settings),
            onPrimaryClick = onOpenAppSettings,
        )

        OnboardingStep.BATTERY_OPTIMIZATION -> OnboardingStepContent(
            modifier = modifier,
            title = stringResource(R.string.onboarding_battery_title),
            body = stringResource(R.string.onboarding_battery_body),
            primaryLabel = stringResource(R.string.onboarding_battery_grant),
            onPrimaryClick = onRequestBatteryExemption,
            secondaryLabel = stringResource(R.string.onboarding_battery_skip),
            onSecondaryClick = onSkipBatteryOptimization,
        )

        OnboardingStep.DONE -> Unit
    }
}

@Composable
private fun OnboardingStepContent(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondaryClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
        }
        Column(modifier = Modifier.padding(top = 24.dp)) {
            Button(onClick = onPrimaryClick) {
                Text(text = primaryLabel)
            }
            if (secondaryLabel != null) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onSecondaryClick) {
                        Text(text = secondaryLabel)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenLegalNoticePreview() {
    MaterialTheme {
        OnboardingScreen(step = OnboardingStep.LEGAL_NOTICE)
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenBatteryPreview() {
    MaterialTheme {
        OnboardingScreen(step = OnboardingStep.BATTERY_OPTIMIZATION)
    }
}
