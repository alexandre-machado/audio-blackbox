package cc.machado.audioblackbox.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Colour override shared by every card-level primary CTA button in the app -- Dashboard's Save
 * and forward-recording start/stop, and Settings' Apply -- `MaterialTheme.colorScheme.primary`
 * (the [FlightOrange] brand colour, per issue #225's fixed cockpit `ColorScheme`) instead of
 * whatever colour a plain `Button` would otherwise default to.
 *
 * Issue #221: the forward-recording start/stop buttons shipped with no `colors = ...` argument at
 * all, so they fell through to `MaterialTheme.colorScheme.primary`, which at the time was the
 * wallpaper-derived dynamic colour, while every sibling card-level CTA carried a hardcoded
 * [FlightOrange] override instead. Centralising it here turns "the two card-level primary CTAs
 * agree" into a compile-time fact rather than four separate literals someone has to keep in sync
 * by eye -- and the disabled variants are part of the same fact: dropping them is how a disabled
 * button becomes illegible.
 *
 * Issue #225: now that `primary`/`onPrimary` in the fixed cockpit `ColorScheme` *are* the brand
 * orange/white pair, this reads them from the theme instead of the [FlightOrange] / `Color.White`
 * literals it used before -- the theme is the single source of truth for what "primary CTA colour"
 * means, and this helper stops being a second place that could drift from it.
 *
 * Deliberately not used by Gallery's play/pause button: that one is an icon-only
 * `FilledIconButton` inside a compact per-row action group, not a card-level primary CTA, and
 * keeps its own `IconButtonDefaults.filledIconButtonColors` call. Nor by the Dashboard's
 * secondary/notice-level buttons (the `OutlinedButton`s and notice `Button`s), which are correct
 * to keep Material's defaults.
 */
@Composable
fun primaryCtaButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
)
