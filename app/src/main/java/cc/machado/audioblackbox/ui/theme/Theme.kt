package cc.machado.audioblackbox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's one and only [androidx.compose.material3.ColorScheme]: the fixed avionics/cockpit
 * palette from `docs/design/model.html`, always dark, never derived from the system wallpaper or
 * the system light/dark setting.
 *
 * Issue #225 (owner decision, 2026-08-29): **dark always.** Before this, [AudioBlackboxTheme] built
 * its scheme from `dynamicLight/DarkColorScheme(context)` on API 31+ and fell back to stock
 * `lightColorScheme()`/`darkColorScheme()` according to `isSystemInDarkTheme()` -- i.e. a scheme
 * derived from the device wallpaper and the system setting, not the app's own cockpit design
 * language. `Color.kt`'s avionics constants existed but were never wired into the `ColorScheme`
 * itself, which is why ~30 call sites across Dashboard/Gallery/Settings had to import and apply
 * them directly, bypassing the theme (and why the forward-recording CTA could silently drift onto
 * the dynamic accent instead of brand orange -- issue #221). `Theme.kt` now builds a single
 * [darkColorScheme] from the cockpit ground and annunciator palette, so a call site that reads
 * `MaterialTheme.colorScheme.*` gets the right color by construction instead of by convention.
 *
 * `AGENTS.md` §5's superseded "dynamic color" rule (formerly `AGENTS.md:70-71`) is recorded, not
 * deleted, alongside this change.
 */
private val CockpitColorScheme = darkColorScheme(
    // Cockpit ground -- model.html:37-41.
    background = CockpitSlate,
    onBackground = TextStencil,
    surface = CockpitPanel,
    onSurface = TextStencil,
    surfaceVariant = CockpitPanel,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = CockpitSlate,
    surfaceContainerLow = CockpitPanel,
    surfaceContainer = CockpitPanel,
    surfaceContainerHigh = CockpitPanelRaised,
    surfaceContainerHighest = CockpitPanelRaised,
    outline = CockpitBorderStrong,
    outlineVariant = CockpitBorderStrong,
    inverseSurface = TextStencil,
    inverseOnSurface = CockpitSlate,
    scrim = Color.Black,

    // Semantic annunciator roles -- AGENTS.md §5's "semantic colour-role rules".
    // Primary: the brand color, reserved for the card-level primary CTA (never a state color).
    primary = FlightOrange,
    onPrimary = Color.White,
    primaryContainer = FlightOrangeContainer,
    onPrimaryContainer = FlightOrange,
    inversePrimary = FlightOrangeDark,
    // Secondary: no dedicated model.html role. Chosen here as the "active" accent for
    // low-emphasis selected state (e.g. FloatingBottomBar's selected-tab indicator), matching
    // model.html's `.nav-tab-item.active { color: var(--color-flight-orange); }` -- ambiguous
    // call, flagged in the PR body.
    secondary = FlightOrangeLight,
    onSecondary = Color.White,
    secondaryContainer = FlightOrangeContainer,
    onSecondaryContainer = FlightOrange,
    // Tertiary: telemetry cyan, the one remaining brand hue with no other M3 role.
    tertiary = TelemetryCyan,
    onTertiary = Color.White,
    tertiaryContainer = TelemetryCyan.copy(alpha = 0.2f),
    onTertiaryContainer = TelemetryCyan,
    // Error: master warning / safety red.
    error = WarningRed,
    onError = Color.White,
    errorContainer = SafetyRedTag,
    onErrorContainer = Color.White,
)

/**
 * Applies the fixed cockpit [ColorScheme] to [content]. Material 3 (stable 1.4.0 line, see
 * `[stack]` in `.agents/team.toml`) remains the component library and interaction model underneath
 * the theme -- this is about color/branding only, not about swapping frameworks or adopting
 * Material 3 Expressive. Typography/shapes are left at [MaterialTheme]'s own defaults for the same
 * reason.
 */
@Composable
fun AudioBlackboxTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CockpitColorScheme,
        content = content,
    )
}
