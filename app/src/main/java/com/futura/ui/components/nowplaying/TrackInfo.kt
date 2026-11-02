// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/TrackInfo.kt
//
// Phase 2 — Now Playing track information block.
//
// ── Responsibility ────────────────────────────────────────────────────────
// Renders the track identity strip that sits directly below the kinetic
// waveform scrubber on the Now Playing screen. Per the Phase 2 design
// spec's "Hero Player Section":
//
//     "Track Info: Bold track title, glowing artist name, and a
//      'DUET / SYNCED LYRICS AVAILABLE' chip."
//
// Three elements stacked vertically and centred:
//
//   1. Track title — bold white, single line, ellipsized. The visual
//      anchor of the block.
//   2. Artist name — cyan with a soft cyan text-glow (the spec's
//      "glowing artist name"). Glow is a `Shadow` in the TextStyle
//      rather than a `drawBehind` radial because shadows are hardware-
//      accelerated glyph operations and are cheaper per character than
//      a rectangular gradient behind the text.
//   3. "DUET / SYNCED LYRICS AVAILABLE" chip — cyan glassmorphic pill,
//      rendered only when `hasSyncedLyrics` is true. Hidden entirely
//      when lyrics are not available, so a track without lyrics gets a
//      clean title + artist block with no chip crowding the layout.
//
// ── Component boundary ────────────────────────────────────────────────────
// Purely presentational. Title, artist, and the lyrics-availability flag
// all arrive as parameters. This component does not:
//
//   • Load track metadata from any source (NowPlayingViewModel or the
//     database will supply these strings).
//   • Format the artist list (callers may pre-join multiple artists
//     into a single string).
//   • Decide whether lyrics are truly available (the caller determines
//     this via OfflineLyricSynchronizer / LyricParser once those files
//     land).
//
// That boundary means NowPlayingScreen can wire TrackInfo today with
// placeholder strings and swap to real NowPlayingViewModel values with
// zero changes to this file.
//
// ── Artist glow details ──────────────────────────────────────────────────
// The glow is a `Shadow` in the artist's TextStyle:
//
//     Shadow(
//         color  = FuturaColors.Cyan.copy(alpha = 0.55f),
//         offset = Offset.Zero,   // glow radiates evenly, not directional
//         blurRadius = 10f
//     )
//
// `blurRadius = 10f` is tuned so the halo extends ~2-3 px beyond the
// glyph outlines — visible at any standard text size, never bleeding
// into the surrounding title or chip. `alpha = 0.55f` keeps it as a
// halo (an accent) rather than a duplicate of the text itself.
//
// ── Typography choices ──────────────────────────────────────────────────
//   • Title: SansSerif, 22sp, Bold, slight negative letter-spacing —
//     reads as a bold display headline, matches the Now Playing stub.
//   • Artist: SansSerif, 14sp, Medium, slight positive letter-spacing —
//     reads as a subordinate line, with the glow carrying the visual
//     weight rather than raw size.
//   • Chip: Monospace, 9sp, ALL CAPS, 1.2sp letter-spacing — matches
//     every other metadata chip in the app (TopBar format badge,
//     WakeUpOverlayScreen alarm badge, the Now Playing stub's lyrics
//     chip).
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                      (generated from delivered resources)
//   • com.futura.ui.theme.FuturaColors  (delivered in theme/Theme.kt)
//   • com.futura.ui.theme.FuturaTheme   (delivered, Preview only)
//
// All other imports resolve to artifacts declared in
// gradle/libs.versions.toml:
//   • androidx.compose.foundation.*          (BOM 2024.10.00)
//   • androidx.compose.material3.*           (BOM 2024.10.00)
//   • androidx.compose.ui.*                  (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview
//
// String resources used (all present in res/values/strings.xml):
//   nowplaying_lyrics_available_chip
//
// No future project class or file is referenced.

package com.futura.ui.components.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
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
 * Track title, artist (with cyan glow), and optional synced-lyrics chip.
 *
 * @param title   The track title. Rendered bold white, ellipsized at
 *                [titleMaxLines].
 * @param artist  The artist (or pre-joined artist list). Rendered in
 *                cyan with a soft glow, ellipsized at [artistMaxLines].
 * @param modifier Optional outer modifier applied to the root column.
 * @param hasSyncedLyrics When true, the "DUET / SYNCED LYRICS AVAILABLE"
 *                chip renders below the artist. When false, no chip
 *                occupies space — the block reads cleanly as just
 *                title + artist.
 * @param titleMaxLines Maximum lines for the title. Default 1 matches
 *                the design's "single bold headline" intent; callers can
 *                raise it for accessibility modes where extra lines are
 *                acceptable.
 * @param artistMaxLines Maximum lines for the artist line.
 */
@Composable
fun TrackInfo(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    hasSyncedLyrics: Boolean = false,
    titleMaxLines: Int = 1,
    artistMaxLines: Int = 1
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Track title — bold white display headline ────────────────
        Text(
            text = title,
            color = FuturaColors.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = (-0.3).sp
        )

        Spacer(Modifier.height(4.dp))

        // ── Artist — cyan with glow via TextStyle.shadow ─────────────
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = FuturaColors.Cyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center,
                shadow = Shadow(
                    color = FuturaColors.Cyan.copy(alpha = 0.55f),
                    offset = Offset.Zero,
                    blurRadius = 10f
                )
            ),
            maxLines = artistMaxLines,
            overflow = TextOverflow.Ellipsis
        )

        // ── Synced lyrics chip — hidden when !hasSyncedLyrics ────────
        if (hasSyncedLyrics) {
            Spacer(Modifier.height(10.dp))
            LyricsAvailableChip()
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Lyrics chip
// ═════════════════════════════════════════════════════════════════════════
//
// Cyan glassmorphic pill, identical treatment to the TopBar's format
// badge so the two cyan chips read as the same component family. Purely
// informational — no tap handler. When lyrics panels get a "jump to
// lyrics" affordance in Phase 3, this chip becomes clickable via an
// onClick parameter; until then, keeping it inert is the correct
// boundary.

@Composable
private fun LyricsAvailableChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = FuturaColors.Cyan.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.nowplaying_lyrics_available_chip),
            color = FuturaColors.Cyan,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            maxLines = 1
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Previews
// ═════════════════════════════════════════════════════════════════════════
//
// Three previews:
//   • Lyrics available — shows the chip.
//   • No lyrics — clean title + artist, chip absent.
//   • Long strings — demonstrates ellipsis behaviour at maxLines = 1.

@Preview(
    name = "TrackInfo — with lyrics",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun TrackInfoPreviewWithLyrics() {
    FuturaTheme {
        TrackInfo(
            title = "Midnight Protocol",
            artist = "SYNTH DIVISION",
            hasSyncedLyrics = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(
    name = "TrackInfo — no lyrics",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun TrackInfoPreviewNoLyrics() {
    FuturaTheme {
        TrackInfo(
            title = "Midnight Protocol",
            artist = "SYNTH DIVISION",
            hasSyncedLyrics = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(
    name = "TrackInfo — long strings",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun TrackInfoPreviewLongStrings() {
    FuturaTheme {
        TrackInfo(
            title = "A Very Long Track Title That Should Ellipsize Rather Than Wrap On The Now Playing Screen",
            artist = "Artist Name That Is Also Quite Long And Will Be Truncated",
            hasSyncedLyrics = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}