package cc.machado.audioblackbox.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An avionics chassis card matching `docs/design/model.html`'s `.avionics-card`:
 * - Dark cockpit panel fill ([CockpitPanel])
 * - Translucent border ([CockpitBorderStrong])
 * - 14dp rounded corners ([CARD_SHAPE])
 * - Subtle corner rivet screws (`＋`) in slate on top corners
 */
@Composable
fun AvionicsCard(
    modifier: Modifier = Modifier,
    shape: Shape = CARD_SHAPE,
    showRivets: Boolean = true,
    innerPadding: Dp = CARD_INNER_PADDING,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = CockpitPanel,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (showRivets) {
                // Top-left rivet screw
                Text(
                    text = "＋",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF475569),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 6.dp),
                )
                // Top-right rivet screw
                Text(
                    text = "＋",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF475569),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 6.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
                content = content,
            )
        }
    }
}

/**
 * Top data plate header bar matching `docs/design/model.html`'s `.card-label-bar`:
 * Monospace uppercase label with optional trailing status tag.
 */
@Composable
fun AvionicsCardHeaderBar(
    label: String,
    modifier: Modifier = Modifier,
    tag: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.sp,
            color = TextDim,
        )
        tag?.invoke()
    }
}

/**
 * Stencil tag / badge matching `docs/design/model.html`'s `.top-tag`.
 */
@Composable
fun AvionicsTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FlightOrange,
    containerColor: Color = FlightOrangeContainer,
    borderColor: Color = color.copy(alpha = 0.35f),
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(RADIUS_RIVET),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.04.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * "REMOVE BEFORE FLIGHT" ribbon / tag banner matching `docs/design/model.html`'s `.rbf-tag`.
 * Rendered when capture is idle / on standby.
 */
@Composable
fun RemoveBeforeFlightTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RADIUS_RIVET),
        color = SafetyRedTag,
        border = BorderStroke(1.dp, WarningRed),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Grommet circle
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, CircleShape)
                    .border(1.5.dp, Color(0xFF7F1D1D), CircleShape),
            )
            Text(
                text = text.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.08.sp,
                color = Color.White,
            )
        }
    }
}

/**
 * Flight Data Recorder (FDR) Flight Tape / Buffer RAM progress ruler track
 * matching `docs/design/model.html`'s `.tape-ruler-track` and `.tape-fill`.
 */
@Composable
fun FlightTapeRulerTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1E293B)),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val tickSpacing = 8.dp.toPx()
            val tickCount = (width / tickSpacing).toInt()

            // Draw ruler tick marks
            for (i in 0..tickCount) {
                val x = i * tickSpacing
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Draw orange tape fill
            if (clampedFraction > 0f) {
                val fillWidth = width * clampedFraction
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFEA580C),
                            FlightOrange,
                            FlightOrangeLight,
                        ),
                        startX = 0f,
                        endX = fillWidth,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(fillWidth, height),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )

                // White leading needle mark
                val needleWidth = 3.dp.toPx()
                val needleX = (fillWidth - needleWidth).coerceAtLeast(0f)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(needleX, 0f),
                    size = Size(needleWidth, height),
                )
            }
        }
    }
}

/**
 * Dashed divider matching `docs/design/model.html`'s `1px dashed rgba(255, 255, 255, 0.1)`.
 */
@Composable
fun DashedDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.12f),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f),
        )
    }
}
