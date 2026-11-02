// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/PlaybackDock.kt
//
// Phase 2 — Now Playing persistent playback controls.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The floating transport pill that anchors the bottom of the Now Playing
// screen. Per the Phase 2 design spec:
//
//     "Persistent Playback Controls:
//        Bottom floating control dock (Shuffle, Previous, Large Glowing
//        Play/Pause circle, Next, Loop)."
//
// Delivers:
//
//   1. Shuffle toggle — emerald when enabled, tertiary when disabled.
//      Uses `Modifier.toggleable(role = Role.Switch)` so TalkBack
//      announces "on/off" state correctly.
//   2. Previous track.
//   3. Large glowing Play/Pause circle — 64dp emerald pill with a
//      radial emerald halo behind it. Halo pulses gently when playing,
//      rests at a stable dim value when paused.
//   4. Next track.
//   5. Loop toggle — same treatment as shuffle.
//
// ── Component boundary ────────────────────────────────────────────────────
// Purely presentational. Every piece of state that outlives a single
// frame arrives as a parameter, every interaction leaves via a callback:
//
//   • isPlaying, isShuffleEnabled, isLoopEnabled → parameters
//   • onPlayPauseClick, onPreviousClick, onNextClick,
//     onShuffleClick, onLoopClick → callbacks
//
// No ViewModel, engine bridge, MediaSession, or queue service is
// touched. The caller (NowPlayingScreen → NowPlayingViewModel once that
// file lands) owns every behaviour; this component just renders and
// delegates.
//
// ── Play/pause glow: why it lives here and not in an art overlay ────────
// The glow is drawn with `Modifier.drawBehind` on the same Box that
// holds the emerald circle, so it translates with the button if the
// caller later adds scroll or scale animations. Drawing it in a
// sibling overlay would require synchronized position animation and
// introduce a clip boundary between the two layers. `drawBehind` is
// applied *before* `.clip(CircleShape)` in the modifier chain, so the
// halo bleeds outward past the circle's edge — matching the "glowing"
// language already used on the Wake-Up Overlay clock (WakeUpOverlayScreen
// `drawBehind` + emerald radial) and the HeroPlayer artwork aura.
//
// When `isPlaying` is true the halo oscillates in a 1.8 s reverse loop
// (0.55 → 1.0 peak alpha) — the same cadence as the Wake-Up Overlay's
// "ROULETTE QUEUE READY" subtitle pulse, so the two "playing" surfaces
// share a visual rhythm. When paused the halo holds at 0.55 with no
// per-frame animation cost.
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
//   • androidx.compose.material.icons.*      (material-icons-extended)
//   • androidx.compose.material3.*           (BOM 2024.10.00)
//   • androidx.compose.ui.*                  (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview
//
// String resources used (all present in res/values/strings.xml):
//   nowplaying_play_pause_cd
//   nowplaying_next_cd
//   nowplaying_prev_cd
//   nowplaying_shuffle_cd
//   nowplaying_loop_cd
//
// No future project class or file is referenced.

package com.futura.ui.components.nowplaying

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.futura.R
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Floating playback transport pill.
 *
 * @param isPlaying Current play/pause state. Drives the icon glyph and
 *        the pulsing behaviour of the play/pause glow.
 * @param onPlayPauseClick Invoked on tap of the large centre circle.
 * @param modifier Optional outer modifier.
 * @param isShuffleEnabled Whether shuffle is currently active. Drives
 *        the shuffle icon tint (emerald vs tertiary) and the TalkBack
 *        switch state.
 * @param isLoopEnabled Whether loop is currently active. Drives the
 *        loop icon tint and the TalkBack switch state.
 * @param onShuffleClick Invoked on tap of the shuffle button; the caller
 *        decides whether this enables or disables shuffle.
 * @param onPreviousClick Invoked on tap of the previous-track button.
 * @param onNextClick Invoked on tap of the next-track button.
 * @param onLoopClick Invoked on tap of the loop button; the caller
 *        decides whether this enables or disables looping.
 */
@Composable
fun PlaybackDock(
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier,
    isShuffleEnabled: Boolean = false,
    isLoopEnabled: Boolean = false,
    onShuffleClick: () -> Unit = {},
    onPreviousClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onLoopClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = FuturaColors.SurfaceRaised,
                shape = RoundedCornerShape(32.dp)
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ToggleTransportIcon(
            icon = Icons.Filled.Shuffle,
            contentDescription = stringResource(R.string.nowplaying_shuffle_cd),
            isActive = isShuffleEnabled,
            onClick = onShuffleClick
        )
        TransportIcon(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = stringResource(R.string.nowplaying_prev_cd),
            tint = FuturaColors.White,
            onClick = onPreviousClick
        )
        PlayPauseCircle(
            isPlaying = isPlaying,
            onClick = onPlayPauseClick
        )
        TransportIcon(
            icon = Icons.Filled.SkipNext,
            contentDescription = stringResource(R.string.nowplaying_next_cd),
            tint = FuturaColors.White,
            onClick = onNextClick
        )
        ToggleTransportIcon(
            icon = Icons.Filled.Repeat,
            contentDescription = stringResource(R.string.nowplaying_loop_cd),
            isActive = isLoopEnabled,
            onClick = onLoopClick
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Play/pause circle
// ═════════════════════════════════════════════════════════════════════════
//
// The visual anchor of the dock. Two stacked effects:
//
//   1. A radial emerald halo drawn behind the circle (drawBehind, before
//      clip so it bleeds outward). Animated by an infinite transition
//      ONLY while playing — no per-frame cost when paused, since the
//      `if (isPlaying)` branch returns a constant Float and no animation
//      composable is entered at all.
//   2. A solid emerald circle 64dp in diameter with a true-black glyph.
//
// The gradient's inner stop is intentionally transparent up to 55% of
// the radius, so the halo does not darken or tint the solid emerald
// circle — it only extends the glow outward into the surface behind.

@Composable
private fun PlayPauseCircle(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    // ── Pulse alpha ────────────────────────────────────────────────
    // While playing: an infinite transition oscillates 0.55 → 1.0
    //   over 1.8 s, matching the Wake-Up Overlay subtitle cadence.
    // While paused:  constant 0.55, no animation is composed.
    val pulseAlpha: Float = if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "play-pause-glow")
        val value by transition.animateFloatPulse()
        value
    } else {
        0.55f
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            // ── Glow halo (drawn behind, not clipped) ─────────────────
            .drawBehind {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.maxDimension * 0.95f
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.55f to Color.Transparent,
                            0.72f to FuturaColors.Emerald
                                .copy(alpha = 0.32f * pulseAlpha),
                            0.88f to FuturaColors.Emerald
                                .copy(alpha = 0.10f * pulseAlpha),
                            1.00f to Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    center = center,
                    radius = radius
                )
            }
            // ── Circle (clipped) ──────────────────────────────────────
            .clip(CircleShape)
            .background(FuturaColors.Emerald)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause
                          else Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.nowplaying_play_pause_cd),
            tint = FuturaColors.OLEDBlack,
            modifier = Modifier.size(32.dp)
        )
    }
}

/**
 * Small extension on `rememberInfiniteTransition` that animates a Float
 * between 0.55 and 1.0 with a 1.8 s reverse loop. Extracted only to keep
 * the play/pause composable's body focused on the layout — the caller
 * never sees this as a separate API surface.
 */
@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatPulse() =
    animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "play-pause-glow-value"
    )

// ═════════════════════════════════════════════════════════════════════════
// Simple transport button (previous / next)
// ═════════════════════════════════════════════════════════════════════════
//
// Fixed tint (white). These are not toggles — a tap means "skip", not
// "toggle skipping" — so no `toggleable` semantics apply.

@Composable
private fun TransportIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Toggle transport button (shuffle / loop)
// ═════════════════════════════════════════════════════════════════════════
//
// Uses `Modifier.toggleable(role = Role.Switch)` so TalkBack announces
// the on/off state correctly and the touch pipeline routes taps through
// the standard Compose Foundation selection semantic. The click target
// is a 48dp box (Material minimum) regardless of the 22dp icon inside —
// large enough to hit reliably on any phone.

@Composable
private fun ToggleTransportIcon(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .toggleable(
                value = isActive,
                role = Role.Switch,
                onValueChange = { onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) FuturaColors.Emerald else FuturaColors.TextTertiary,
            modifier = Modifier.size(22.dp)
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Previews
// ═════════════════════════════════════════════════════════════════════════
//
// Four previews covering every visual state:
//   • Idle — paused, no shuffle, no loop.
//   • Playing — pulsing play/pause glow, icon shows Pause glyph.
//   • Playing + shuffle + loop active.
//   • All inactive — the "off" defaults to verify disabled tints.

@Preview(
    name = "PlaybackDock — paused (defaults)",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun PlaybackDockPreviewPaused() {
    FuturaTheme {
        PlaybackDock(
            isPlaying = false,
            onPlayPauseClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(
    name = "PlaybackDock — playing",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun PlaybackDockPreviewPlaying() {
    FuturaTheme {
        PlaybackDock(
            isPlaying = true,
            onPlayPauseClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(
    name = "PlaybackDock — playing + shuffle + loop",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun PlaybackDockPreviewFullState() {
    FuturaTheme {
        PlaybackDock(
            isPlaying = true,
            onPlayPauseClick = {},
            isShuffleEnabled = true,
            isLoopEnabled = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(
    name = "PlaybackDock — all inactive",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun PlaybackDockPreviewAllInactive() {
    FuturaTheme {
        PlaybackDock(
            isPlaying = false,
            onPlayPauseClick = {},
            isShuffleEnabled = false,
            isLoopEnabled = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}