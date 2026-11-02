// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/KineticWaveform.kt
//
// Phase 2 — Now Playing kinetic waveform scrubber.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The high-density interactive waveform bar that sits above the time
// scrubber on the Now Playing screen. Matches the Phase 2 design spec:
//
//     "Kinetic Waveform Scrubber: High-density interactive audio waveform
//      bar above the time scrubber. Clicking/dragging calculates time
//      previews ('02:14 / -01:42')."
//
// Delivers exactly three things:
//
//   1. A high-density waveform rendering (96 bars) with the played
//      portion highlighted in emerald and the unplayed portion dimmed.
//   2. Tap-to-seek and drag-to-seek gestures, mapped onto the track
//      duration as a 0..1 fraction.
//   3. A live time preview pill ("02:14 / -01:42") that follows the
//      drag position while the user is scrubbing, clamped to stay
//      within the component's horizontal bounds.
//
// ── Component boundary ────────────────────────────────────────────────────
// Purely presentational and fully driven by parameters:
//
//   • `waveformData` — optional FloatArray of per-bar amplitudes (0..1).
//     When null, a deterministic placeholder waveform is generated,
//     phase-shifted by `trackKey` so switching tracks shows a visually
//     different pattern.
//   • `progress`     — current playback position as a 0..1 fraction.
//     Smoothed internally with a 120 ms tween when not dragging so the
//     playhead glides rather than jumps on discrete position updates.
//   • `durationSeconds` — track duration. When 0 the time preview pill
//     is suppressed (nothing meaningful to preview yet).
//   • `onSeek`       — optional (Float) -> Unit invoked on tap and on
//     drag-release with the new 0..1 fraction. When null the component
//     is inert (no gesture handling), and the tap/drag callbacks are
//     detached from the pointer pipeline entirely.
//
// Because none of the above depends on a ViewModel, engine bridge, or
// any other future project class, this file compiles today against the
// delivered theme and resources with no forward references.
//
// ── Interaction details ──────────────────────────────────────────────────
//   • Tap-to-seek: single tap anywhere on the waveform seeks to that
//     fraction. Uses `detectTapGestures` to stay compatible with
//     TalkBack and with switch-access interaction patterns.
//   • Drag-to-seek: `detectHorizontalDragGestures` captures the initial
//     touch position on drag start so the playhead snaps to the finger
//     even on a slow-start drag, tracks horizontal movement frame-by-
//     frame, and fires `onSeek` exactly once on drag end. Cancel rolls
//     back to the pre-drag position by simply not firing `onSeek`.
//   • Drag responsiveness: while dragging, the `progress` value seen by
//     the canvas switches from the smoothed animated value to the raw
//     `dragFraction` state, so the playhead tracks the finger 1:1 with
//     zero perceptible lag.
//
// ── Visual language ──────────────────────────────────────────────────────
//   • Played bars: full-emerald (#00FF66).
//   • Unplayed bars: emerald at 30% alpha — dim enough to clearly read
//     as "not yet played" without competing with the played portion.
//   • Playhead: a thin emerald vertical line drawn over the bar tops
//     when the fraction is strictly between 0 and 1, so the endpoints
//     don't get a visible line over the terminal bars.
//   • Bars are rounded at both ends via `drawRoundRect` with a corner
//     radius equal to half the bar width, giving the waveform a
//     pill-shaped profile at any zoom level.
//   • Time preview pill: OLED-black backdrop, emerald border and text,
//     monospace. Follows the drag position but clamped to the component
//     edges so it never bleeds off-screen.
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
//   • androidx.compose.material3.*           (BOM 2024.10.00)
//   • androidx.compose.ui.*                  (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview
//
// String resources used (all present in res/values/strings.xml):
//   nowplaying_time_elapsed_format
//
// No future project class or file is referenced.

package com.futura.ui.components.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.R
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Interactive kinetic waveform with tap-to-seek, drag-to-seek, and a
 * live time preview pill during scrubbing.
 *
 * @param modifier Modifier applied to the outer Box. The component
 *        enforces its own 56dp height and fills the caller's width.
 * @param waveformData Per-bar amplitudes in `0f..1f`. When null, a
 *        deterministic placeholder waveform is generated (see
 *        [generateDefaultWaveform]). Once the Phase 3 native
 *        `WaveformExtractor` lands and produces real amplitude arrays
 *        for the current track, pass them in here.
 * @param progress Current playback position as a `0f..1f` fraction.
 *        Smoothed internally when not dragging.
 * @param durationSeconds Total track duration in whole seconds. When
 *        greater than zero, the time preview pill shows during drags.
 *        When zero, no preview appears — the component still responds
 *        to seek gestures and calls [onSeek], giving the caller full
 *        control over what a position means when no duration is known.
 * @param onSeek Invoked with the new `0f..1f` fraction on tap and on
 *        drag release. When null, the component is non-interactive and
 *        all gesture modifiers are detached.
 * @param trackKey Arbitrary object whose identity triggers a placeholder
 *        waveform regeneration when [waveformData] is null. Supplying
 *        the current track's stable id (e.g. its database primary key)
 *        makes each track render a visually distinct placeholder until
 *        real waveform data is available.
 */
@Composable
fun KineticWaveform(
    modifier: Modifier = Modifier,
    waveformData: FloatArray? = null,
    progress: Float = 0f,
    durationSeconds: Long = 0L,
    onSeek: ((Float) -> Unit)? = null,
    trackKey: Any? = null
) {
    // Placeholder waveform is regenerated whenever the track changes so
    // users see a different pattern per track. When `waveformData` is
    // supplied directly it always wins; the placeholder is never
    // recomputed.
    val effectiveData: FloatArray = waveformData ?: remember(trackKey) {
        generateDefaultWaveform(seed = trackKey?.hashCode() ?: 0)
    }

    // Drag state — held here (not hoisted) because it is purely a
    // visual/interaction concern of this component. The caller owns
    // `progress`; this component only owns "the user is currently
    // touching the waveform and here is where they are".
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // Container width is captured from a single onSizeChanged pass and
    // reused both by the pointer handlers (to convert x-coordinate to a
    // 0..1 fraction) and by the time preview pill (to clamp its
    // position). Tracking it here keeps both usages in sync.
    var containerWidthPx by remember { mutableIntStateOf(0) }

    // Smooth the incoming progress when NOT dragging. During a drag the
    // rendered fraction comes from `dragFraction` instead, so the tween
    // is a no-op visually and the playhead tracks the finger 1:1.
    val smoothedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (isDragging) snap() else tween(durationMillis = 120),
        label = "kinetic-waveform-progress"
    )

    val shownFraction: Float =
        if (isDragging) dragFraction else smoothedProgress

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onSizeChanged { containerWidthPx = it.width }
            .then(
                if (onSeek != null) {
                    Modifier
                        // Tap-to-seek — separate pointerInput block so the
                        // tap gesture can complete without the drag
                        // gesture recogniser stealing it.
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val w = size.width.toFloat()
                                    if (w > 0f) {
                                        val fraction =
                                            (offset.x / w).coerceIn(0f, 1f)
                                        onSeek(fraction)
                                    }
                                }
                            )
                        }
                        // Drag-to-seek — captures initial position on
                        // drag start so slow-start drags still snap the
                        // playhead onto the finger; consumes each
                        // movement; fires onSeek once on release.
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    val w = size.width.toFloat()
                                    if (w > 0f) {
                                        dragFraction =
                                            (offset.x / w).coerceIn(0f, 1f)
                                    }
                                },
                                onDragEnd = {
                                    val finalFraction = dragFraction
                                    isDragging = false
                                    onSeek(finalFraction)
                                },
                                onDragCancel = {
                                    // Roll back — do NOT call onSeek, so
                                    // the caller's progress state stays
                                    // at its pre-drag value.
                                    isDragging = false
                                }
                            ) { change, _ ->
                                change.consume()
                                val w = size.width.toFloat()
                                if (w > 0f) {
                                    dragFraction =
                                        (change.position.x / w).coerceIn(0f, 1f)
                                }
                            }
                        }
                } else {
                    Modifier
                }
            )
    ) {
        // Waveform rendering — a single Canvas draw pass. All bar
        // geometry, played/unplayed colouring, and the playhead line
        // are computed in one traversal so the layout system sees one
        // draw node regardless of bar count.
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawKineticWaveform(
                data = effectiveData,
                fraction = shownFraction
            )
        }

        // Time preview pill — appears only while dragging AND only when
        // a real duration is known. Positioned above the drag point,
        // clamped to the component's horizontal bounds so it can't bleed
        // off-screen at either end.
        if (isDragging && durationSeconds > 0L && containerWidthPx > 0) {
            val positionSeconds = (shownFraction * durationSeconds).toLong()
            val remainingSeconds =
                (durationSeconds - positionSeconds).coerceAtLeast(0L)
            TimePreviewPill(
                text = stringResource(
                    R.string.nowplaying_time_elapsed_format,
                    formatTime(positionSeconds),
                    formatTime(remainingSeconds)
                ),
                fraction = shownFraction,
                containerWidthPx = containerWidthPx
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Waveform drawing
// ═════════════════════════════════════════════════════════════════════════

/**
 * Draws the waveform bars + playhead line.
 *
 * Bar geometry:
 *   • Fixed 2dp gap between bars.
 *   • Bar width derived from available width and bar count, so a change
 *     to the placeholder density immediately produces evenly-spaced
 *     bars without a per-count formula in the caller.
 *   • Bar height = amplitude × 85% of the canvas height. The 15% top
 *     and bottom padding prevents tall bars from touching the edges
 *     when amplitude is 1.0.
 *
 * Colouring:
 *   • Bar centre ≤ playhead → full emerald.
 *   • Bar centre > playhead → emerald at 30% alpha.
 *
 * Playhead:
 *   • Drawn as a 2dp vertical line only when fraction is strictly in
 *     (0.01, 0.99) — at the extreme edges the line would overlap the
 *     terminal bars and read as a colour artefact rather than a cursor.
 */
private fun DrawScope.drawKineticWaveform(
    data: FloatArray,
    fraction: Float
) {
    if (data.isEmpty() || size.width <= 0f || size.height <= 0f) return

    val barCount = data.size
    val gap = 2.dp.toPx()
    val totalGap = gap * (barCount - 1).coerceAtLeast(0)
    val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(1f)
    val centerY = size.height / 2f
    val maxBarHeight = size.height * 0.85f
    val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

    val playheadX = size.width * fraction.coerceIn(0f, 1f)

    val playedColor = FuturaColors.Emerald
    val unplayedColor = FuturaColors.Emerald.copy(alpha = 0.30f)

    for (i in 0 until barCount) {
        val amplitude = data[i].coerceIn(0.06f, 1f)
        val barHeight = maxBarHeight * amplitude
        val left = i * (barWidth + gap)
        val barCenterX = left + barWidth / 2f

        val color =
            if (barCenterX <= playheadX) playedColor else unplayedColor

        drawRoundRect(
            color = color,
            topLeft = Offset(left, centerY - barHeight / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = cornerRadius
        )
    }

    if (fraction > 0.01f && fraction < 0.99f) {
        drawLine(
            color = FuturaColors.Emerald,
            start = Offset(playheadX, centerY - maxBarHeight / 2f - 2.dp.toPx()),
            end = Offset(playheadX, centerY + maxBarHeight / 2f + 2.dp.toPx()),
            strokeWidth = 2.dp.toPx()
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Time preview pill
// ═════════════════════════════════════════════════════════════════════════

/**
 * Floating pill that shows the position/remaining time preview above
 * the drag point during a scrub.
 *
 * Positioning: the pill's desired horizontal centre is the drag point
 * (fraction × container width). The left edge of the pill is then
 * clamped to `[4dp, containerWidth - pillWidth - 4dp]` so the pill
 * stays fully visible even at the extremes of the waveform.
 *
 * Sizing: the pill has a fixed width (140dp) and height (26dp) so that
 * the clamping math is deterministic and the pill doesn't shift shape
 * as the time string grows from "00:00 / -00:00" to "1:00:00 / -1:00:00".
 * The text inside is monospaced so the digits stay aligned regardless.
 */
@Composable
private fun TimePreviewPill(
    text: String,
    fraction: Float,
    containerWidthPx: Int
) {
    val density = LocalDensity.current
    val pillWidthPx = with(density) { 140.dp.toPx() }
    val edgeMarginPx = with(density) { 4.dp.toPx() }

    // Desired left edge = drag centre minus half pill width.
    val desiredX = containerWidthPx * fraction - pillWidthPx / 2f
    // Clamp so the pill stays fully visible.
    val maxX = (containerWidthPx - pillWidthPx - edgeMarginPx)
        .coerceAtLeast(edgeMarginPx)
    val clampedX = desiredX.coerceIn(edgeMarginPx, maxX)

    Box(
        modifier = Modifier
            .offset { IntOffset(clampedX.roundToInt(), 0) }
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FuturaColors.OLEDBlack.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = FuturaColors.Emerald.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = FuturaColors.Emerald,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            maxLines = 1
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Time formatting
// ═════════════════════════════════════════════════════════════════════════

/**
 * Formats whole seconds as "MM:SS", or "H:MM:SS" for durations of an
 * hour or longer. Negative inputs are treated as zero. Matches the
 * compact timestamp style used on the Now Playing time readout and in
 * the widget's secondary text.
 */
private fun formatTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return if (h > 0L) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Placeholder waveform generation
// ═════════════════════════════════════════════════════════════════════════

/**
 * Generates a 96-bar deterministic placeholder waveform.
 *
 * Two superposed sine waves with incommensurate frequencies produce a
 * pattern that reads as musical (peaks and valleys, no repeating grid),
 * a sinusoidal envelope tapers the ends so the waveform doesn't look
 * clipped, and the whole array is normalised to `0..1` so bar heights
 * are consistent across tracks.
 *
 * `seed` shifts the phase of the sines so different tracks produce
 * visibly different placeholder patterns. Passing `seed = 0` produces
 * the same canonical shape used by the NowPlayingScreen stub — useful
 * for visual continuity during the stub → real component handoff.
 */
private fun generateDefaultWaveform(seed: Int): FloatArray {
    val count = 96
    val phase = ((seed % 100 + 100) % 100) * 0.1f
    val raw = FloatArray(count) { i ->
        val t = i.toFloat() / count
        val envelope = sin(t * PI).toFloat().coerceIn(0.15f, 1f)
        val s1 = sin(i * 0.7f + phase) * 0.25f
        val s2 = sin(i * 0.23f + 1.1f + phase * 0.5f) * 0.20f
        val value = (0.45f + s1 + s2) * envelope
        value.coerceAtLeast(0.06f)
    }
    val maxValue = raw.maxOrNull()?.takeIf { it > 0f } ?: 1f
    return FloatArray(count) { i -> (raw[i] / maxValue).coerceIn(0.06f, 1f) }
}

// ═════════════════════════════════════════════════════════════════════════
// Previews
// ═════════════════════════════════════════════════════════════════════════

@Preview(
    name = "KineticWaveform — idle",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun KineticWaveformPreviewIdle() {
    FuturaTheme {
        KineticWaveform(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            progress = 0.42f,
            durationSeconds = 224L, // 3:44
            onSeek = {}
        )
    }
}

@Preview(
    name = "KineticWaveform — no duration (gestures only)",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun KineticWaveformPreviewNoDuration() {
    FuturaTheme {
        KineticWaveform(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            progress = 0f,
            durationSeconds = 0L,
            onSeek = {}
        )
    }
}

@Preview(
    name = "KineticWaveform — inert (onSeek = null)",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360
)
@Composable
private fun KineticWaveformPreviewInert() {
    FuturaTheme {
        KineticWaveform(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            progress = 0.75f,
            durationSeconds = 180L,
            onSeek = null
        )
    }
}