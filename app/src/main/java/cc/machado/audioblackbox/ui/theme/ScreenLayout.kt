package cc.machado.audioblackbox.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shared layout tokens for the app's three top-level screens (Dashboard, Gallery, Settings --
 * issue #221).
 *
 * Before this, each screen carried its own private literal: `DashboardScreen.kt` had a
 * `DASHBOARD_PADDING` constant, `SettingsScreen.kt` repeated the same `24.dp` as a bare literal,
 * and `GalleryScreen.kt` used a different number outright (`16.dp` gutter, `12.dp` section
 * spacing, `16.dp` card radius). Nothing forced the three to agree, which is exactly how Gallery
 * drifted. These are the single source of truth all three screens read from now.
 */

/** Outer gutter applied around each screen's top-level scrollable content column. */
val SCREEN_GUTTER = 24.dp

/** Vertical spacing between a screen's top-level sections (header, cards, notices). */
val SECTION_SPACING = 16.dp

/**
 * Corner radius scale from `docs/design/model.html:47-52` (the design system's source of truth,
 * not the Compose code) -- named after that file's own custom-property names so the mapping stays
 * auditable. Neither Dashboard/Settings' pre-#221 `20.dp` section-card radius nor Gallery's
 * pre-#221 `16.dp` card radius appears anywhere in this scale; both are replaced by [RADIUS_LG]
 * below rather than either literal being promoted as-is.
 */
val RADIUS_RIVET = 4.dp
val RADIUS_SM = 8.dp
val RADIUS_MD = 14.dp
val RADIUS_LG = 14.dp
val RADIUS_FULL = 4.dp

/** Corner radius for a screen's section-level [androidx.compose.material3.Card]s -- `--radius-lg`. */
val CARD_SHAPE = RoundedCornerShape(RADIUS_LG)

/** Inner padding applied to the content of a screen's section-level cards. */
val CARD_INNER_PADDING = 16.dp

/** Size of the orange icon badge in each screen's header row -- see `ScreenHeader`. */
val HEADER_BADGE_SIZE = 44.dp

/** Corner radius of the header badge. */
val HEADER_BADGE_SHAPE = RoundedCornerShape(12.dp)

/** Size of the icon drawn inside the header badge. */
val HEADER_BADGE_ICON_SIZE = 24.dp
