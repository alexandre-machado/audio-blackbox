package cc.machado.audioblackbox.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reviewable mockup for issue #335's aviation-panel button pair proposal. Not wired to
 * `DashboardScreen` -- these previews exist purely so the primitives added to
 * [AvionicsPanelButton]/[AvionicsPanelButtonRow] can be inspected in the states the real
 * Save/forward-recording actions need, before `@dev` wires them into `EngineCard` as a follow-up.
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
            saveCaption = "03:45 BUFFERED",
            saveEnabled = true,
            liveLabel = "LIVE",
            liveCaption = "STARTS A NEW FILE",
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Enabled, buffer full", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairEnabledFull() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = "BUFFER FULL · 30 MIN",
            saveEnabled = true,
            liveLabel = "LIVE",
            liveCaption = "STARTS A NEW FILE",
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Disabled, no audio yet", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairDisabled() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = "NO AUDIO YET",
            saveEnabled = false,
            liveLabel = "LIVE",
            liveCaption = "STARTS A NEW FILE",
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Save in progress", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairSaving() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveLabel = "SAVING",
            saveCaption = "WRITING TO DISK…",
            saveEnabled = true,
            saveInProgress = true,
            liveLabel = "LIVE",
            liveCaption = "STARTS A NEW FILE",
            liveLedColor = FlightOrange,
        )
    }
}

@Preview(name = "335 - Continuous recording (STOP state)", widthDp = 411, showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun PreviewPanelPairLiveRecording() {
    AudioBlackboxTheme {
        PanelPairDemo(
            saveCaption = "12:03 BUFFERED",
            saveEnabled = true,
            liveLabel = "STOP",
            liveCaption = "00:42 LIVE",
            liveLedColor = WarningRed,
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
            saveCaption = "03:45 BUFFERED",
            saveEnabled = true,
            liveLabel = "LIVE",
            liveCaption = "STARTS A NEW FILE",
            liveLedColor = FlightOrange,
        )
    }
}

@Composable
private fun PanelPairDemo(
    saveLabel: String = "SAVE",
    saveCaption: String,
    saveEnabled: Boolean,
    saveInProgress: Boolean = false,
    liveLabel: String,
    liveCaption: String,
    liveLedColor: androidx.compose.ui.graphics.Color,
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
                            label = saveLabel,
                            onClick = {},
                            enabled = saveEnabled,
                            inProgress = saveInProgress,
                            caption = saveCaption,
                            ledColor = FlightOrange,
                            contentDescription = "Save the audio recorded so far",
                        )
                    },
                    live = {
                        AvionicsPanelButton(
                            label = liveLabel,
                            onClick = {},
                            enabled = true,
                            caption = liveCaption,
                            ledColor = liveLedColor,
                            contentDescription = if (liveLabel == "STOP") {
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
