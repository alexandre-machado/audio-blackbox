package cc.machado.audioblackbox.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 *
 * Issue #213: [OnboardingStep.REQUEST_RECORD_AUDIO], [OnboardingStep.REQUEST_NOTIFICATIONS] and
 * [OnboardingStep.REQUEST_BATTERY_OPTIMIZATION] render nothing here -- the caller launches the
 * real OS prompt/Intent as a side effect of the step changing, so the user sees the native
 * dialog directly with no app-drawn page underneath it.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    modifier: Modifier = Modifier,
    onAcceptConsent: () -> Unit = {},
    onDeclineConsent: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onRequestRecordAudio: () -> Unit = {},
    onRequestNotifications: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    when (step) {
        OnboardingStep.CONSENT -> ConsentStepContent(
            modifier = modifier,
            onAccept = onAcceptConsent,
            onDecline = onDeclineConsent,
            onOpenPrivacyPolicy = onOpenPrivacyPolicy,
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

        OnboardingStep.REQUEST_RECORD_AUDIO,
        OnboardingStep.REQUEST_NOTIFICATIONS,
        OnboardingStep.REQUEST_BATTERY_OPTIMIZATION,
        OnboardingStep.DONE -> Unit
    }
}

/**
 * The single consent screen (issue #213). Combines what used to be three full-screen steps
 * (legal notice, audio rationale, notifications rationale) into one prominent disclosure that
 * covers why (continuous background capture), what (rolling in-memory buffer) and how (never
 * transmitted, written to disk only on explicit Save) before any permission is requested --
 * Play's prominent-disclosure policy, see `docs/release/play-store.md`. The decline action is
 * the compliance-critical part: it must be as visible as accept, and it must exit without
 * recording consent.
 */
@Composable
private fun ConsentStepContent(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = stringResource(R.string.onboarding_consent_title), style = MaterialTheme.typography.headlineSmall)
        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.onboarding_consent_body), style = MaterialTheme.typography.bodyLarge)
        }
        Column(modifier = Modifier.padding(top = 16.dp)) {
            TextButton(onClick = onOpenPrivacyPolicy) {
                Text(text = stringResource(R.string.onboarding_consent_privacy_link))
            }
        }
        Column(modifier = Modifier.padding(top = 24.dp)) {
            Button(onClick = onAccept) {
                Text(text = stringResource(R.string.onboarding_consent_accept))
            }
            Column(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onDecline) {
                    Text(text = stringResource(R.string.onboarding_consent_decline))
                }
            }
        }
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
private fun OnboardingScreenConsentPreview() {
    MaterialTheme {
        OnboardingScreen(step = OnboardingStep.CONSENT)
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenAudioRationalePreview() {
    MaterialTheme {
        OnboardingScreen(step = OnboardingStep.AUDIO_RATIONALE)
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenAudioPermanentlyDeniedPreview() {
    MaterialTheme {
        OnboardingScreen(step = OnboardingStep.AUDIO_PERMANENTLY_DENIED)
    }
}
