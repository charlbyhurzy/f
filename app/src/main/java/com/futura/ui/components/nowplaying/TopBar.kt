// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/TopBar.kt
//
// Phase 2 — Now Playing top bar.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The persistent header strip at the top of NowPlayingScreen. Three
// elements, matching the Phase 2 design spec exactly:
//
//   Left    — "ALARM DECK" chip with a system-lock glyph. Collapses back
//             to the Phase 1 Wake-Up Overlay.
//   Center  — Track format badge ("FLAC · 24-bit / 96kHz"). Tapping opens
//             the slide-down Stream Inspector modal.
//   Right   — Equalizer quick-toggle icon. Tinted emerald when EQ is
//             enabled, white when disabled.
//
// ── Component boundary ────────────────────────────────────────────────────
// TopBar is purely presentational. It owns no state:
//
//   • The alarm time, the actual track format, and the EQ-enabled flag
//     all arrive as parameters — TopBar does not reach into any
//     ViewModel, repository, or engine bridge.
//   • All three interactions are callback parameters. The caller
//     (NowPlayingScreen) decides what "tap alarm deck", "tap format
//     badge", and "tap EQ" mean — navigates back, opens the Stream
//     Inspector, toggles the DSP chain, etc.
//
// That boundary is deliberate. StreamInspectorModal.kt and the EQ
// surface are separate files in the project tree, neither delivered yet.
// Encoding the routing decisions here would force a forward reference
// to those files. Keeping TopBar dumb means NowPlayingScreen can wire
// it today with no-op callbacks, and swap in real routing the moment
// those files land — without touching TopBar.
//
// ── Signature compatibility ───────────────────────────────────────────────
// NowPlayingScreen.kt's current TopBarStub(...) call is:
//
//     TopBarStub(onAlarmDeck = onAlarmDeck)
//
// The real TopBar requires three callbacks, but the other two can be
// no-ops during the stub phase:
//
//     TopBar(
//         onAlarmDeckClick   = onAlarmDeck,
//         onFormatBadgeClick = {},
//         onEqToggleClick    = {},
//     )
//
// The formatBadgeText and isEqEnabled parameters have sensible defaults,
// so the callsite does not need to pass them until the underlying
// NowPlayingViewModel provides real values. One-line swap.
//
// ── Design compliance ─────────────────────────────────────────────────────
//   • Glassmorphic chips: `SurfaceBase` backdrop at 55% alpha + 1dp
//     accent-coloured border + 20dp rounded corners — matches the
//     WakeUpOverlayScreen status badges exactly.
//   • Emerald for the Alarm Deck chip (primary accent), cyan for the
//     format badge (secondary/informational), white/emerald for the EQ
//     toggle (state-dependent).
//   • Monospace ALL-CAPS typography for both chips — same console voice
//     used on the alarm badge and the launcher shortcut labels.
//   • 48dp fixed row height with the chips vertically centred. Chips
//     are ~32dp tall visually, matching Material chip height guidance,
//     while the row guarantees breathing room for the surrounding
//     content.
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                      (generated from delivered resources)
//   • com.futura.ui.theme.FuturaColors  (delivered in theme/Theme.kt)
//
// All other imports resolve to artifacts declared in
// gradle/libs.versions.toml:
//   • androidx.compose.foundation.*              (BOM 2024.10.00)
//   • androidx.compose.material.icons.*          (material-icons-core / -extended)
//   • androidx.compose.material3.*               (BOM 2024.10.00)
//   • androidx.compose.ui.*                      (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview (ui-tooling-preview, `implementation`)
//
// No future project class or file is referenced. Every string resource
// used below (nowplaying_top_alarm_deck, nowplaying_alarm_deck_cd,
// nowplaying_format_badge_default, nowplaying_stream_inspector_open_cd,
// nowplaying_eq_toggle_cd) is present in res/values/strings.xml.

package com.futura.ui.components.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.R
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Now Playing top bar — Alarm Deck chip, format badge, EQ toggle.
 *
 * @param onAlarmDeckClick   Invoked when the left "ALARM DECK" chip is
 *                           tapped. Caller navigates back to
 *                           MainRoute.WakeUpOverlay (Phase 1 overlay).
 * @param onFormatBadgeClick Invoked when the centre format badge is
 *                           tapped. Caller opens the Stream Inspector
 *                           modal (StreamInspectorModal.kt once
 *                           delivered; no-op until then).
 * @param onEqToggleClick    Invoked when the right EQ icon is tapped.
 *                           Caller toggles the DSP equalizer chain.
 * @param modifier           Optional outer modifier.
 * @param formatBadgeText    Text shown in the format badge. Defaults to
 *                           the placeholder from strings.xml
 *                           ("FLAC · 24-bit / 96kHz") until a real
 *                           NowPlayingViewModel supplies the current
 *                           track's resolved format.
 * @param isEqEnabled        Whether the EQ chain is currently active.
 *                           Drives the icon tint: emerald when true,
 *                           white when false.
 */
@Composable
fun TopBar(
    onAlarmDeckClick: () -> Unit,
    onFormatBadgeClick: () -> Unit,
    onEqToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
    formatBadgeText: String = stringResource(R.string.nowplaying_format_badge_default),
    isEqEnabled: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlarmDeckChip(onClick = onAlarmDeckClick)

        Spacer(Modifier.width(8.dp))

        // The badge expands to absorb all horizontal space between the
        // two fixed-width siblings, which centres it within the row —
        // matching the design's "Center: Track format badge" intent.
        FormatBadgeChip(
            text = formatBadgeText,
            onClick = onFormatBadgeClick,
            modifier = Modifier.weight(1f)
        )

        Spacer(Modifier.width(8.dp))

        EqToggleButton(
            isEnabled = isEqEnabled,
            onClick = onEqToggleClick
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Alarm Deck chip — left
// ═════════════════════════════════════════════════════════════════════════
//
// A glassmorphic pill with an emerald-bordered lock glyph + "ALARM DECK"
// label. Uses `Icons.Filled.Lock` per the design's "system lock icon"
// requirement, not an arrow — the lock signals that the music surface
// is anchored to the alarm session that started it.

@Composable
private fun AlarmDeckChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = FuturaColors.Emerald.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                onClickLabel = stringResource(R.string.nowplaying_alarm_deck_cd),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null, // chip label carries the meaning
            tint = FuturaColors.Emerald,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.nowplaying_top_alarm_deck),
            color = FuturaColors.Emerald,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Format badge chip — centre
// ═════════════════════════════════════════════════════════════════════════
//
// Cyan-accented glassmorphic pill. Text is supplied by the caller so
// the badge can show the actual per-track format once the ViewModel
// supplies it, not a hardcoded sample. The text is centred and
// ellipsized so a very long format string (e.g. one that adds bit-depth
// and channel count) still fits without wrapping.

@Composable
private fun FormatBadgeChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = FuturaColors.Cyan.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                onClickLabel = stringResource(R.string.nowplaying_stream_inspector_open_cd),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = FuturaColors.Cyan,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// EQ toggle — right
// ═════════════════════════════════════════════════════════════════════════
//
// Material 3 IconButton provides the standard 48dp tap target. The
// `isEnabled` parameter is a *visual* state (whether the DSP equalizer
// chain is currently active), not a Compose `enabled` toggle — tapping
// the button is always allowed and always invokes onClick; the callback
// decides whether to enable or disable EQ.

@Composable
private fun EqToggleButton(
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.Equalizer,
            contentDescription = stringResource(R.string.nowplaying_eq_toggle_cd),
            tint = if (isEnabled) FuturaColors.Emerald else FuturaColors.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Preview
// ═════════════════════════════════════════════════════════════════════════
//
// Two previews: EQ disabled (default) and EQ enabled. Renders against
// FuturaTheme so colours and typography exactly match runtime.

@Preview(name = "TopBar — EQ off", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun TopBarPreviewEqOff() {
    FuturaTheme {
        TopBar(
            onAlarmDeckClick = {},
            onFormatBadgeClick = {},
            onEqToggleClick = {}
        )
    }
}

@Preview(name = "TopBar — EQ on", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun TopBarPreviewEqOn() {
    FuturaTheme {
        TopBar(
            onAlarmDeckClick = {},
            onFormatBadgeClick = {},
            onEqToggleClick = {},
            formatBadgeText = "FLAC · 24-bit / 48kHz",
            isEqEnabled = true
        )
    }
}