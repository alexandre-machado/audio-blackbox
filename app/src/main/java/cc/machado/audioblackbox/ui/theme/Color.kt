package cc.machado.audioblackbox.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * US Aviation & Aerospace Design Tokens
 * Based on FED-STD-595 / AMS-STD-595 (#12197 International Orange),
 * MIL-STD Cockpit Lighting (NVIS Green), Korry Annunciators, and NASA standards.
 */
val FlightOrange = Color(0xFFFF5722) // FED-STD-595 #12197 International Orange
val FlightOrangeHover = Color(0xFFF4511E) // model.html --color-flight-orange-hover
val FlightOrangeLight = Color(0xFFFF8A50)
val FlightOrangeDark = Color(0xFFBF360C)
val FlightOrangeContainer = Color(0x33FF5722)
val FlightOrangeGlow = Color(0x59FF5722) // model.html --color-flight-orange-glow (rgba(255,87,34,0.35))

val AvionicsGreen = Color(0xFF10B981) // NVIS Cockpit Green
val AvionicsGreenGlow = Color(0x6610B981) // model.html --color-nvis-green-glow (rgba(16,185,129,0.4))
val AvionicsGreenDim = Color(0xFF064E3B)

val CautionAmber = Color(0xFFF59E0B) // Korry Master Caution Amber
val CautionAmberGlow = Color(0x66F59E0B) // model.html --color-caution-amber-glow (rgba(245,158,11,0.4))
val CautionAmberDim = Color(0xFF78350F)

val WarningRed = Color(0xFFEF4444) // Master Warning / Safety Red
val WarningRedGlow = Color(0x66EF4444) // model.html --color-warning-red-glow (rgba(239,68,68,0.4))
val SafetyRedTag = Color(0xFFB91C1C) // "REMOVE BEFORE FLIGHT" red

val TelemetryCyan = Color(0xFF06B6D4) // Cockpit Telemetry Cyan
val TelemetryCyanGlow = Color(0x4D06B6D4) // model.html --color-telemetry-cyan-glow (rgba(6,182,212,0.3))

val CockpitSlate = Color(0xFF0A0E17) // Cockpit Dark Base -- model.html --color-cockpit-bg
val CockpitPanel = Color(0xFF111827) // Avionics Card Container -- model.html --color-cockpit-panel
val CockpitPanelRaised = Color(0xFF1A2234) // model.html --color-cockpit-panel-raised
val CockpitBorder = Color(0x14FFFFFF) // model.html --color-cockpit-border (rgba(255,255,255,0.08))
val CockpitBorderStrong = Color(0x29FFFFFF) // model.html --color-cockpit-border-strong (rgba(255,255,255,0.16))
val CockpitRivetBorder = Color(0xFF334155) // Hardware border (opaque; distinct from the translucent tokens above)

// Text scale -- model.html:43-45. Compose text previously fell back to Material's onSurface /
// onSurfaceVariant, which under dynamic color were wallpaper-derived rather than these fixed
// values (AGENTS.md #5's "no text-color-scale constants" gap, closed by issue #225: the fixed
// cockpit ColorScheme now maps its on* roles to these).
val TextStencil = Color(0xFFF8FAFC) // model.html --color-text-stencil
val TextMuted = Color(0xFF94A3B8) // model.html --color-text-muted
val TextDim = Color(0xFF64748B) // model.html --color-text-dim
