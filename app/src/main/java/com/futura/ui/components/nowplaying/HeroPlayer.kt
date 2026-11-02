// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/HeroPlayer.kt
//
// Phase 2 — Now Playing hero player.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The visual centerpiece of the Now Playing screen: a rounded square
// album-artwork container wrapped in a reactive bass-glow aura. Matches
// the Phase 2 design spec's "Hero Player Section":
//
//     "Central rounded square container for 3D/high-res album artwork
//      with a reactive bass-glow aura behind it."
//
// ── Component boundary (why HeroPlayer is just artwork + aura) ────────────
// The project tree places KineticWaveform.kt, TrackInfo.kt, and the
// other Now Playing surfaces as *sibling* component files, not children
// of HeroPlayer. That granularity keeps each surface independently
// testable and lets the caller (NowPlayingScreen) lay them out in the
// exact proportions the design calls for. HeroPlayer therefore owns
// precisely two things:
//
//   1. The album artwork container (rounded square, content-scaled).
//   2. The reactive emerald bass-glow aura drawn behind it.
//
// Track title, waveform scrubber, time readout, and the lyrics chip are
// separate components that NowPlayingScreen stacks above and below
// HeroPlayer.
//
// ── Artwork source ────────────────────────────────────────────────────────
// `artworkModel: Any?` is passed straight through to Coil's
// SubcomposeAsyncImage, so the caller can supply whatever Coil can load
// — a `String` URL, a `Uri`, a `File`, a `Drawable`, or a raw `Bitmap`.
// When the model is null or loading/error, a placeholder (music-note
// glyph on the surface slate) is rendered so the layout never collapses.
//
// Coil is declared in gradle/libs.versions.toml (`coil = "2.7.0"`,
// `coil-compose` artifact) and applied via `implementation(libs.coil.compose)`
// in app/build.gradle.kts. No future project class is required for Coil
// to work.
//
// ── Reactive bass-glow aura ──────────────────────────────────────────────
// The aura is a radial gradient drawn with `Modifier.drawBehind`, which
// by design does not clip to the node's layout bounds. Placed BEFORE
// `.clip(...)` in the modifier chain, the aura draws OUTSIDE the artwork
// container and bleeds into the surrounding OLED black — the "glow
// behind it" halo the spec asks for.
//
// The `bassLevel` parameter drives both the aura's peak intensity and
// its radius, so a bass hit causes a visible bloom:
//
//   bassLevel = 0.0  →  radius ≈ 0.75 × artwork size, peak α ≈ 0.18
//   bassLevel = 1.0  →  radius ≈ 1.05 × artwork size, peak α ≈ 0.40
//
// The raw `bassLevel` is smoothed with a short (60 ms) `tween` before
// being applied, so callers can feed raw per-frame audio-tap values
// without the halo jittering on single-sample noise. When the real
// AudioTap source is wired in Phase 3, no change is required here.
//
// ── Interactive affordance ───────────────────────────────────────────────
// `onArtworkClick` is optional. When non-null, the artwork becomes
// tappable (typically to expand into a full-screen artwork viewer or to
// toggle a now-playing overlay). When null, the artwork is inert and no
// click modifier is attached, preserving whatever touches the caller
// wants to route elsewhere.
//
// ── Design compliance ─────────────────────────────────────────────────────
//   • Rounded square container — `RoundedCornerShape(24.dp)`, matching
//     the NowPlayingScreen stub's placeholder geometry.
//   • Placeholder surface — `FuturaColors.SurfaceBase` (#1A1F26), matching
//     the RemoteViews widget album-art placeholder and every other
//     glass-morphic container in the app.
//   • Emerald aura — `FuturaColors.Emerald` (#00FF66), same primary
//     accent as WakeUpOverlayScreen, the TopBar Alarm Deck chip, and the
//     launcher shortcut icons.
//   • Placeholder glyph — `Icons.Filled.LibraryMusic` at 45% emerald
//     alpha, 72dp, identical to the NowPlayingScreen stub's placeholder
//     so the transition from stub to real component is visually seamless.
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                      (generated from delivered resources)
//   • com.futura.ui.theme.FuturaColors  (delivered in theme/Theme.kt)
//   • com.futura.ui.theme.FuturaTheme   (delivered, Preview only)
//
// All other imports resolve to artifacts declared in
// gradle/libs.versions.toml:
//   • androidx.compose.animation.core.*      (BOM 2024.10.00)
//   • androidx.compose.foundation.*          (BOM 2024.10.00)
//   • androidx.compose.material.icons.*      (material-icons-core — LibraryMusic)
//   • androidx.compose.material3.*           (BOM 2024.10.00)
//   • androidx.compose.ui.*                  (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview
//   • coil.compose.SubcomposeAsyncImage      (coil = "2.7.0")
//
// No future project class or file is referenced.

package com.futura.ui.components.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.futura.R
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Hero album-artwork container with a reactive bass-glow aura.
 *
 * @param modifier Modifier applied to the outer Box. **The caller MUST
 *        constrain the width** — HeroPlayer itself only enforces
 *        `aspectRatio(1f)`, so a modifier such as
 *        `Modifier.fillMaxWidth(0.72f)` is typical. Without a width
 *        constraint the component collapses to zero size, as with any
 *        aspectRatio-only box.
 * @param artworkModel Anything Coil can load — a `String` URL, `Uri`,
 *        `File`, `Drawable`, or `Bitmap`. Pass `null` while a current
 *        track is not available and the placeholder glyph renders.
 * @param artworkContentDescription Screen-reader description for the
 *        artwork. Defaults to the app-wide "Album artwork" string so the
 *        component is accessible out of the box; callers may override
 *        with a richer per-track description (e.g.
 *        "Album art for {title} by {artist}").
 * @param bassLevel Current low-frequency energy in `0f..1f`. Drives the
 *        intensity and radius of the emerald aura. Smoothed internally
 *        with a 60 ms tween, so callers may feed raw per-frame values.
 * @param onArtworkClick Optional tap handler. When non-null the artwork
 *        becomes clickable; when null it is inert.
 */
@Composable
fun HeroPlayer(
    modifier: Modifier = Modifier,
    artworkModel: Any? = null,
    artworkContentDescription: String = stringResource(R.string.nowplaying_album_art_cd),
    bassLevel: Float = 0f,
    onArtworkClick: (() -> Unit)? = null
) {
    // Smooth the raw bass input to prevent per-sample jitter from
    // producing a visibly flickering halo. 60 ms is short enough that a
    // bass hit blooms essentially instantly but long enough to suppress
    // single-frame spikes.
    val smoothedBass by animateFloatAsState(
        targetValue = bassLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 60),
        label = "hero-bass-glow"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            // ── Reactive bass-glow aura ───────────────────────────────
            // Draws BEFORE .clip() in the modifier chain, so the aura is
            // not clipped by the rounded-corner shape and bleeds into
            // the surrounding black background. drawBehind respects
            // no clip by default — the halo would only be clipped if an
            // ancestor sets clipToBounds, which the NowPlayingScreen
            // layout does not.
            .drawBehind {
                val center = Offset(size.width / 2f, size.height / 2f)
                val peakAlpha = 0.18f + smoothedBass * 0.22f
                val midAlpha = 0.04f + smoothedBass * 0.08f
                // 0.75 → 1.05 × the artwork's larger dimension. At rest
                // the halo extends just past the artwork corners; at
                // bass=1 it reaches ~30% of the artwork's size beyond
                // each edge.
                val glowRadius = size.maxDimension * (0.75f + smoothedBass * 0.30f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.5f to FuturaColors.Emerald.copy(alpha = peakAlpha * 0.5f),
                            0.7f to FuturaColors.Emerald.copy(alpha = peakAlpha),
                            0.9f to FuturaColors.Emerald.copy(alpha = midAlpha),
                            1.0f to Color.Transparent
                        ),
                        center = center,
                        radius = glowRadius
                    ),
                    center = center,
                    radius = glowRadius
                )
            }
            // ── Artwork container (clipped) ───────────────────────────
            .clip(RoundedCornerShape(24.dp))
            .background(FuturaColors.SurfaceBase)
            .then(
                if (onArtworkClick != null) {
                    Modifier.clickable(onClick = onArtworkClick)
                } else {
                    Modifier
                }
            )
    ) {
        if (artworkModel == null) {
            ArtworkPlaceholder(contentDescription = artworkContentDescription)
        } else {
            SubcomposeAsyncImage(
                model = artworkModel,
                contentDescription = artworkContentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { ArtworkPlaceholder(contentDescription = null) },
                error = { ArtworkPlaceholder(contentDescription = null) }
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Placeholder glyph
// ═════════════════════════════════════════════════════════════════════════
//
// A centred music-note icon on the slate surface. Rendered while the
// artwork model is null or while Coil is loading/erroring. Matches the
// NowPlayingScreen stub's AlbumArtPlaceholder exactly (72dp LibraryMusic
// at 45% emerald), so the visual handoff from stub to real component is
// invisible.

@Composable
private fun ArtworkPlaceholder(contentDescription: String?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.LibraryMusic,
            contentDescription = contentDescription,
            tint = FuturaColors.Emerald.copy(alpha = 0.45f),
            modifier = Modifier.size(72.dp)
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Previews
// ═════════════════════════════════════════════════════════════════════════
//
// Three previews spanning the aura's dynamic range. All render the
// placeholder glyph (no real artwork is available at preview time), but
// the aura intensity difference between them is visible.

@Preview(
    name = "HeroPlayer — quiet (bass 0.0)",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 320
)
@Composable
private fun HeroPlayerPreviewQuiet() {
    FuturaTheme {
        HeroPlayer(
            modifier = Modifier.fillMaxWidth(0.72f),
            bassLevel = 0f
        )
    }
}

@Preview(
    name = "HeroPlayer — mid (bass 0.5)",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 320
)
@Composable
private fun HeroPlayerPreviewMid() {
    FuturaTheme {
        HeroPlayer(
            modifier = Modifier.fillMaxWidth(0.72f),
            bassLevel = 0.5f
        )
    }
}

@Preview(
    name = "HeroPlayer — peak (bass 1.0)",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 320
)
@Composable
private fun HeroPlayerPreviewPeak() {
    FuturaTheme {
        HeroPlayer(
            modifier = Modifier.fillMaxWidth(0.72f),
            bassLevel = 1f
        )
    }
}