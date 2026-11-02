// futura-audio/app/src/main/java/com/futura/ui/theme/Theme.kt
//
// Central Compose theme for Futura Audio.
//
// ── Scope ─────────────────────────────────────────────────────────────────
// Per the tree, Color.kt and Typography.kt are marked "inline in FuturaTheme"
// — meaning this single file delivers the entire theming surface:
//
//   • FuturaColors          public palette object (brand anchors + surfaces
//                           + state colours) mirroring values/colors.xml
//   • FuturaTypography      Material 3 Typography with monospace display
//                           styles for the clock / Now Playing time
//   • FuturaShapes          rounded-corner scale for glassmorphic cards
//   • FuturaTheme           the @Composable entry point used at the root
//                           of every Compose tree in the app
//
// ── Design intent ─────────────────────────────────────────────────────────
// The app is 100% dark, forever. There is no light theme. Rationale:
//
//   1. The visual identity is OLED true black (#000000) — the reason the
//      app saves power on OLED panels and the reason the alarm overlay
//      reads as a system surface rather than an app.
//   2. Material You dynamic-colour would override the emerald/cyan brand
//      palette on every device that supports it, destroying consistency.
//      This app is deliberately NOT dynamic-colour.
//   3. The design spec is explicit across all six phases: pure black
//      canvas, emerald and cyan accents, glass-morphism cards. There is
//      nothing to translate to a light palette.
//
// FuturaTheme therefore takes no `darkTheme` parameter. It always installs
// the same dark scheme. If light mode is ever needed, that is a mechanical
// addition — but no design work has asked for it, so adding it now would
// only create surface area to maintain.
//
// ── Surface tint: transparent ─────────────────────────────────────────────
// Material 3 tints elevated surfaces by blending `surfaceTint` (default:
// primary) into the surface colour. For this app that would make every
// raised card pick up a subtle emerald wash on top of the intended true
// black — producing a colour that is neither the design's #1A1F26 slate
// nor its #000000 base. Setting `surfaceTint = Color.Transparent` disables
// elevation tinting entirely. Depth is expressed through GlassCard's
// explicit backdrop-blur + glowing borders, not through Material's tint.
//
// ── Monospace display styles ──────────────────────────────────────────────
// The design's "massive digital time display in bold monospace typography"
// (WakeUpOverlayScreen, SleepDock DimClock, CarMode HUD, Now Playing
// timestamp) is delivered through the three `display*` typography slots.
// Each is `FontFamily.Monospace` at a heavy weight with tight tracking.
//
// Everything else (headlines, titles, body, labels) uses the platform's
// default sans-serif. Custom fonts are intentionally NOT introduced —
// none are in the file tree, and bundled font files would be additional
// binaries that cannot be authored as text.
//
// ── Palette ↔ colors.xml sync ─────────────────────────────────────────────
// Every Colour value below that also appears in values/colors.xml uses
// the identical ARGB hex. Both files must stay in sync: XML is consulted
// by drawables, RemoteViews, and system dialogs; Kotlin is consulted by
// Compose. When adding a new colour, add it to both, in the same order.
//
// Where a Material 3 slot has no exact match in colors.xml (e.g. the
// `surfaceContainer*` cascade), the closest existing value is used, and
// a comment marks the choice. No new hex values are introduced by this
// file — the palette in colors.xml remains authoritative.

package com.futura.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═════════════════════════════════════════════════════════════════════════
// Public palette
// ═════════════════════════════════════════════════════════════════════════
//
// Access via `FuturaColors.Emerald` etc. anywhere a Material 3 semantic
// slot isn't precise enough (e.g. when a composable needs the literal
// "emergency red" rather than "error", or when a custom visualisation
// needs the palette's amber for a warning band). For everyday UI,
// `MaterialTheme.colorScheme.*` remains preferred.

object FuturaColors {

    // ── Brand anchors (values/colors.xml: futura_*) ──────────────────────
    val OLEDBlack = Color(0xFF000000)   // futura_black / bg_oled_black
    val Emerald = Color(0xFF00FF66)     // futura_emerald
    val Cyan = Color(0xFF00E5FF)        // futura_cyan
    val Amber = Color(0xFFFFB000)       // futura_amber
    val White = Color(0xFFFFFFFF)       // futura_white

    // ── Surfaces (values/colors.xml: bg_*) ───────────────────────────────
    val SurfaceBase = Color(0xFF1A1F26)      // bg_surface
    val SurfaceRaised = Color(0xFF232A33)    // bg_surface_raised
    val SurfaceRecessed = Color(0xFF12161B)  // bg_surface_recessed

    // ── Text roles (values/colors.xml: text_*) ───────────────────────────
    val TextPrimary = Color(0xFFFFFFFF)      // text_primary
    val TextSecondary = Color(0xFF00E5FF)    // text_secondary
    val TextTertiary = Color(0xFF7A8A99)     // text_tertiary
    val TextDisabled = Color(0xFF4A525C)     // text_disabled

    // ── Interaction state overlays (values/colors.xml: ripple_*) ─────────
    val RippleEmerald = Color(0x3300FF66)    // ripple_emerald
    val RippleCyan = Color(0x3300E5FF)       // ripple_cyan
    val RippleNeutral = Color(0x33FFFFFF)    // ripple_neutral

    // ── Feedback states (values/colors.xml: state_*) ─────────────────────
    val Success = Color(0xFF00FF66)          // state_success
    val Warning = Color(0xFFFFB000)          // state_warning
    val Error = Color(0xFFFF3B5C)            // state_error
    val Info = Color(0xFF00E5FF)             // state_info

    // ── Phase-specific colour families (used by future screens) ──────────
    val AlarmArmed = Emerald
    val AlarmSnoozed = Amber
    val AlarmOff = TextTertiary
    val AlarmFiring = Error

    val PrivacyIndicatorActive = Error
    val PrivacyIndicatorInactive = TextTertiary
    val PrivacyRedactedBar = Color(0xFF3A434F) // privacy_redacted_bar

    val SleepClock = Color(0xFFFF7A00)         // sleep_clock
    val SleepClockDim = Color(0xFF7A3A00)      // sleep_clock_dim
}

// ═════════════════════════════════════════════════════════════════════════
// Typography
// ═════════════════════════════════════════════════════════════════════════
//
// Display styles are Monospace: the design's "bold monospace typography"
// for WakeUpOverlayScreen's clock, SleepDock's DimClock, CarMode's HUD,
// and the Now Playing time scrubber timestamps.
//
// Sizes follow Material 3's scale (57 / 45 / 36) but are nudged for the
// "massive digital time" intent:
//   displayLarge  →  64sp  (default 57) — the Wake-Up Overlay clock
//   displayMedium →  48sp  (default 45) — the Now Playing elapsed time
//   displaySmall  →  36sp  (default 36) — small dashboard clocks
//
// Letter spacing is tightened on display styles so "06:30 AM" reads as a
// single engraved glyph block, not as six separate digits.

val FuturaTypography: Typography = Typography(

    // ── Display (monospace, bold, tight) ─────────────────────────────────
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,
        lineHeight = 68.sp,
        letterSpacing = (-2).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1.5).sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1).sp
    ),

    // ── Headline (sans-serif, screen titles) ────────────────────────────
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // ── Title (sans-serif, card headings, section titles) ───────────────
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ── Body (sans-serif, track titles, descriptions, list rows) ────────
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ── Label (sans-serif, ALL-CAPS badge text, chip text, buttons) ─────
    // Uppercase labels carry wider tracking so words like
    // "ALARM ARMED // 07:00 AM" and "ON-DEVICE REAL-TIME SEPARATION ACTIVE"
    // stay legible at small sizes and feel engineered, not cramped.
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    )
)

// ═════════════════════════════════════════════════════════════════════════
// Shapes
// ═════════════════════════════════════════════════════════════════════════
//
// Matches the glassmorphic container vocabulary used across the design:
// medium (16dp) for cards, large (24dp) for modal sheets and dialogs,
// extraLarge (32dp) for the Now Playing hero artwork container.
// small (8dp) and extraSmall (4dp) are for chips, badges, and buttons.
private val FuturaShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// ═════════════════════════════════════════════════════════════════════════
// Dark colour scheme
// ═════════════════════════════════════════════════════════════════════════
//
// Every Material 3 slot is filled so that any Material component
// (Button, Card, OutlinedTextField, Dialog, NavigationBar, Switch…) renders
// with the Futura palette out of the box. Where a slot maps directly to a
// value in colors.xml, the exact hex is used. Where a slot has no
// counterpart in the palette (the `surfaceContainer*` cascade, inverse
// colours), the closest existing value is chosen and annotated.
private val FuturaDarkColorScheme = darkColorScheme(

    // ── Primary: emerald ──────────────────────────────────────────────────
    // The app's signature accent. Play button glow, active tab indicator,
    // focus ring, primary action button background.
    primary = Color(0xFF00FF66),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF003D1A),          // derived: emerald at ~25% → dark
    onPrimaryContainer = Color(0xFF7AFFBA),        // derived: emerald lightened
    inversePrimary = Color(0xFF00873A),            // derived: emerald darkened

    // ── Secondary: cyan ───────────────────────────────────────────────────
    // Artist names, sub-headings, metadata chips, "informational" state.
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF003640),        // derived: cyan at ~22% → dark
    onSecondaryContainer = Color(0xFF7AEFFF),      // derived: cyan lightened

    // ── Tertiary: amber ───────────────────────────────────────────────────
    // Snooze countdown, warning dialogs, "attention required" surfaces.
    tertiary = Color(0xFFFFB000),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF3D2900),         // derived: amber at ~24% → dark
    onTertiaryContainer = Color(0xFFFFD87A),       // derived: amber lightened

    // ── Background & surface: true OLED black ─────────────────────────────
    // The critical values. Any deviation here (e.g. Material's default
    // #1C1B1F) breaks the OLED power-saving story and produces a visible
    // grey edge against the status bar on some panels.
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),

    // surfaceVariant is used by Material components as a secondary surface
    // (e.g. the strip behind a NavigationBar). Use the slate card colour
    // so it reads as a "raised" region against the black canvas.
    surfaceVariant = Color(0xFF1A1F26),            // bg_surface
    onSurfaceVariant = Color(0xFF7A8A99),          // text_tertiary

    // Disable elevation tinting — see file-header note.
    surfaceTint = Color.Transparent,

    // ── Inverse surface (rarely used in an all-dark app) ─────────────────
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),

    // ── Error ─────────────────────────────────────────────────────────────
    error = Color(0xFFFF3B5C),                     // state_error
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4A0F18),            // derived: red at ~28% → dark
    onErrorContainer = Color(0xFFFF9AA8),          // derived: red lightened

    // ── Outline ──────────────────────────────────────────────────────────
    // Used by OutlinedButton, OutlinedTextField borders, and by the
    // glassmorphic card borders when they are not explicitly emerald.
    outline = Color(0xFF7A8A99),                   // text_tertiary
    outlineVariant = Color(0xFF3A434F),            // privacy_redacted_bar

    // ── Scrim ────────────────────────────────────────────────────────────
    scrim = Color(0xFF000000),

    // ── Surfaces cascade (used by NavigationBar, BottomSheet, elevated Cards)
    // Map to the three palette surfaces plus two interpolations so Material
    // components stack without visual jumps.
    surfaceBright = Color(0xFF232A33),             // bg_surface_raised
    surfaceDim = Color(0xFF0A0E12),                // darker than bg_surface_recessed
    surfaceContainerLowest = Color(0xFF0A0E12),    // darker than recessed
    surfaceContainerLow = Color(0xFF12161B),       // bg_surface_recessed
    surfaceContainer = Color(0xFF1A1F26),          // bg_surface
    surfaceContainerHigh = Color(0xFF232A33),      // bg_surface_raised
    surfaceContainerHighest = Color(0xFF2A323C)    // lightened from raised
)

// ═════════════════════════════════════════════════════════════════════════
// FuturaTheme
// ═════════════════════════════════════════════════════════════════════════
//
// The single entry point every Compose tree in the app passes through.
// Always installs the dark scheme; no `darkTheme` parameter exists by
// design (see file-header note on "no light theme").
//
// Usage from an Activity or @Preview:
//
//     FuturaTheme {
//         // any composables
//     }
//
// Nothing here touches the window itself. System bars, edge-to-edge, and
// status-bar icon appearance are configured by MainActivity via
// `enableEdgeToEdge()` and are declared statically in
// res/values/themes.xml / values-night/themes.xml so the pre-Compose
// window frame is already true black.
@Composable
fun FuturaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FuturaDarkColorScheme,
        typography = FuturaTypography,
        shapes = FuturaShapes,
        content = content
    )
}