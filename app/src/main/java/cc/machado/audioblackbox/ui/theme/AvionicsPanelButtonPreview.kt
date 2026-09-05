package cc.machado.audioblackbox.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.machado.audioblackbox.R
import java.util.Locale

/**
 * Reviewable mockup for issue #335's aviation-panel button pair proposal. [DashboardScreen]'s
 * `ActionPanelRow` is the wired equivalent -- this file stays as the design proposal's own preview
 * surface, now pulling every visible string from the real resources `ActionPanelRow` reads instead
 * of the English literals the original proposal hardcoded, so a translation gap here would fail the
 * same way it would on the real screen. See [PreviewPanelPairPortuguese] for the localisation check
 * `@rev` asked for on PR #339: nothing below is reachable only in English.
 *
 * Every group below is the *same* two calls to [AvionicsPanelButton] -- only the state arguments
 * change -- which is the point: one primitive, every affordance the two deleted `Button`s used to
 * carry (enabled, disabled-with-reason, in-progress, start/stop).
 */
@Preview(name = "335 - Enabled, buffer partial", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairEnabledPartial() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = stringResource(R.string.dashboard_panel_save_caption_buffered, "03:45"),
            saveEnabled = true,
            liveLabel = stringResource(R.string.dashboard_panel_live_label),
            liveCaption = stringResource(R.string.dashboard_panel_live_caption_idle),
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Enabled, buffer full", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairEnabledFull() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = stringResource(R.string.dashboard_panel_save_caption_full, 30),
            saveEnabled = true,
            liveLabel = stringResource(R.string.dashboard_panel_live_label),
            liveCaption = stringResource(R.string.dashboard_panel_live_caption_idle),
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Disabled, no audio yet", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairDisabled() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = stringResource(R.string.dashboard_panel_save_caption_no_audio),
            saveEnabled = false,
            liveLabel = stringResource(R.string.dashboard_panel_live_label),
            liveCaption = stringResource(R.string.dashboard_panel_live_caption_idle),
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Save in progress", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairSaving() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveLabel = stringResource(R.string.dashboard_panel_save_saving_label),
            saveCaption = stringResource(R.string.dashboard_panel_save_caption_saving),
            saveEnabled = true,
            saveInProgress = true,
            liveLabel = stringResource(R.string.dashboard_panel_live_label),
            liveCaption = stringResource(R.string.dashboard_panel_live_caption_idle),
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Continuous recording (STOP state, pulsing LED)", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairLiveRecording() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = stringResource(R.string.dashboard_panel_save_caption_buffered, "12:03"),
            saveEnabled = true,
            liveLabel = stringResource(R.string.dashboard_panel_live_stop_label),
            liveCaption = stringResource(R.string.dashboard_panel_live_caption_recording, "00:42"),
            liveLedColor = FlightOrange,
            livePulsing = true,
        )
    }
}

@Preview(
    name = "335 - Smallest supported width (360dp), stacked",
    widthDp = 360,
    showBackground = true,
    backgroundColor = 0xFF0A0E17,
)
@Composable
private fun PreviewPanelPairCompactWidth() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = stringResource(R.string.dashboard_panel_save_caption_buffered, "03:45"),
            saveEnabled = true,
            liveLabel = stringResource(R.string.dashboard_panel_live_label),
            liveCaption = stringResource(R.string.dashboard_panel_live_caption_idle),
            liveLedColor = FlightOrange,
        )
    }
}

/**
 * `@rev`'s condition on PR #339: localisation was never verified for this design proposal. Forces
 * a pt-BR `Configuration`/`Context` the same way `ScreenLayoutTest`'s locale tests do, over the
 * two states most likely to overflow the panel button's fixed-height row -- the disabled reason
 * (a full sentence-length string in English already) and the live/STOP caption, which carries a
 * pulsing LED as well as translated text.
 */
@Preview(name = "335 - pt-BR, disabled + live recording", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairPortuguese() {
    val ptConfig = Configuration(LocalConfiguration.current).apply {
        setLocale(Locale.forLanguageTag("pt-BR"))
    }
    val ptContext = LocalContext.current.createConfigurationContext(ptConfig)
    CompositionLocalProvider(
        LocalConfiguration provides ptConfig,
        LocalContext provides ptContext,
    ) {
        AudioBlackboxTheme {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PanelPairDemo(
                    saveCaption = stringResource(R.string.dashboard_panel_save_caption_no_audio),
                    saveEnabled = false,
                    liveLabel = stringResource(R.string.dashboard_panel_live_label),
                    liveCaption = stringResource(R.string.dashboard_panel_live_caption_idle),
                    liveLedColor = FlightOrange,
                )
                PanelPairDemo(
                    saveCaption = stringResource(R.string.dashboard_panel_save_caption_buffered, "12:03"),
                    saveEnabled = true,
                    liveLabel = stringResource(R.string.dashboard_panel_live_stop_label),
                    liveCaption = stringResource(R.string.dashboard_panel_live_caption_recording, "00:42"),
                    liveLedColor = FlightOrange,
                    livePulsing = true,
                )
            }
        }
    }
}

@Composable
private fun PanelPairDemo(
    saveLabel: String = stringResource(R.string.dashboard_panel_save_label),
    saveCaption: String,
    saveEnabled: Boolean,
    saveInProgress: Boolean = false,
    liveLabel: String,
    liveCaption: String,
    liveLedColor: Color,
    livePulsing: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CockpitSlate)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "ENGINE CARD -- ACTION PANEL",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = TextDim,
        )
        AvionicsCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AvionicsCardHeaderBar(label = "ACTION PANEL // BUFFER + LIVE")
                AvionicsPanelButtonRow(
                    save = {
                        AvionicsPanelButton(
                            label = saveLabel.uppercase(),
                            onClick = {},
                            enabled = saveEnabled,
                            inProgress = saveInProgress,
                            caption = saveCaption.uppercase(),
                            ledColor = FlightOrange,
                            contentDescription = "Save the audio recorded so far",
                        )
                    },
                    live = {
                        AvionicsPanelButton(
                            label = liveLabel.uppercase(),
                            onClick = {},
                            enabled = true,
                            pulsing = livePulsing,
                            caption = liveCaption.uppercase(),
                            ledColor = liveLedColor,
                            contentDescription = if (livePulsing) {
                                "Stop continuous recording"
                            } else {
                                "Save the past and keep recording"
                            },
                        )
                    },
                )
            }
        }
    }
}
