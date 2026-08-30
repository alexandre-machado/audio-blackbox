package cc.machado.audioblackbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.ui.theme.DashedDivider
import cc.machado.audioblackbox.ui.theme.FlightOrange
import cc.machado.audioblackbox.ui.theme.FlightOrangeContainer
import cc.machado.audioblackbox.ui.theme.HEADER_BADGE_ICON_SIZE
import cc.machado.audioblackbox.ui.theme.HEADER_BADGE_SHAPE
import cc.machado.audioblackbox.ui.theme.HEADER_BADGE_SIZE

/**
 * The 44dp orange icon-badge header row shared by Dashboard, Gallery and Settings: a badge next to
 * a title/subtitle pair with an avionics data plate dashed divider at the bottom (matching
 * `docs/design/model.html`'s `.app-header-plate`).
 */
@Composable
fun ScreenHeader(
    icon: Painter,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = HEADER_BADGE_SHAPE,
                color = FlightOrangeContainer,
                border = BorderStroke(1.dp, FlightOrange.copy(alpha = 0.4f)),
                modifier = Modifier.size(HEADER_BADGE_SIZE),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        tint = FlightOrange,
                        modifier = Modifier.size(HEADER_BADGE_ICON_SIZE),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = FlightOrange,
                )
            }
        }
        DashedDivider()
    }
}
