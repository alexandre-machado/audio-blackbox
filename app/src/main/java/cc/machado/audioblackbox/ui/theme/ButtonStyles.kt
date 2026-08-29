package cc.machado.audioblackbox.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colour override shared by every card-level primary CTA button in the app -- Dashboard's Save
 * and forward-recording start/stop, and Settings' Apply -- [FlightOrange] instead of
 * `MaterialTheme.colorScheme.primary`, which on API 31+ is the wallpaper-derived dynamic colour.
 *
 * Issue #221: the forward-recording start/stop buttons shipped with no `colors = ...` argument at
 * all, so they fell through to that dynamic colour while every sibling card-level CTA carried
 * this override. Centralising it here turns "the two card-level primary CTAs agree" into a
 * compile-time fact rather than four separate literals someone has to keep in sync by eye -- and
 * the disabled variants are part of the same fact: dropping them is how a disabled button becomes
 * illegible.
 *
 * Deliberately not used by Gallery's play/pause button: that one is an icon-only
 * `FilledIconButton` inside a compact per-row action group, not a card-level primary CTA, and
 * keeps its own `IconButtonDefaults.filledIconButtonColors` call. Nor by the Dashboard's
 * secondary/notice-level buttons (the `OutlinedButton`s and notice `Button`s), which are correct
 * to keep Material's defaults.
 */
@Composable
fun primaryCtaButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = FlightOrange,
    contentColor = Color.White,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
)
