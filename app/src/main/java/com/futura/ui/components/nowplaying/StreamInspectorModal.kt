// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/StreamInspectorModal.kt
//
// Phase 2 — Now Playing Stream Inspector modal.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The slide-down diagnostic panel opened when the user taps the format
// badge in TopBar. Displays the live audio-stream telemetry for the
// currently playing track:
//
//   • Sample Rate     — e.g. "96 kHz / 24-bit"
//   • Audio HAL       — e.g. "AAudio"
//   • Latency         — e.g. "8.3 ms" (formatted via
//                       nowplaying_stream_latency_value_format)
//   • Buffer          — e.g. "192 frames" (formatted via
//                       nowplaying_stream_buffer_value_format)
//   • Bit-Perfect     — ACTIVITY pill (emerald) or BYPASSED (amber)
//
// ── Component boundary ────────────────────────────────────────────────────
// Purely presentational. It owns:
//   • The scrim (dim, tap-to-dismiss).
//   • The enter/exit slide animation (slide from screen top, fade scrim).
//   • Layout of the diagnostic rows.
//
// It does NOT own:
//   • The data — every value arrives as a parameter with a sensible
//     default, so the caller wires real NowPlayingViewModel values when
//     that file lands without touching this component.
//   • The visibility state — the caller holds `visible` and reacts to
//     `onDismiss`.
//   • The routing — there is exactly one callback: onDismiss.
//
// That boundary means this file can ship today even though the real
// `nowplaying/StreamInspector.kt` service (which will expose a
// StateFlow<StreamInfo>) is not yet delivered. When it is, the
// NowPlayingScreen callsite feeds the real values in; this component's
// signature and behaviour stay identical.
//
// ── Visual language ──────────────────────────────────────────────────────
// Notification-shade treatment, matching the "slide-down modal" spec:
//   • Panel anchored to the top of the screen, full width, rounded
//     bottom corners only — reads as a drawer pulled down from above.
//   • Panel background extends edge-to-edge (touches status-bar line);
//     content is inset below the status bar via safeDrawing insets.
//   • Emerald 1dp border + rounded bottom corners — matches the
//     glassmorphic chip vocabulary in WakeUpOverlayScreen and TopBar.
//   • Header row: monospace ALL-CAPS "STREAM INSPECTOR" + close button.
//   • Diagnostic rows: monospace label (tertiary) → monospace value
//     (white) — same pairing as the alarm badges.
//   • Bit-Perfect pill: 18% alpha background of the state colour +
//     1dp border of the same, matching the TopBar chip pattern.
//
// ── Modal behaviour ──────────────────────────────────────────────────────
// Two behaviours are implemented here rather than delegated to a
// framework ModalBottomSheet, because the "slide from top" animation
// isn't achievable with `ModalBottomSheet` without fighting its
// bottom-anchor geometry:
//
//   1. Scrim dismiss — a tap anywhere on the scrim closes the modal.
//   2. Tap-through suppression — a tap on the panel's *empty* area
//      (not on a child clickable) is consumed by an inline
//      `detectTapGestures` no-op, so the underlying Now Playing screen
//      cannot be interacted with while the modal is open.
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                      (generated from delivered resources)
//   • com.futura.ui.theme.FuturaColors  (delivered in theme/Theme.kt)
//   • com.futura.ui.theme.FuturaTheme   (delivered in theme/Theme.kt, Preview only)
//
// All other imports resolve to artifacts declared in
// gradle/libs.versions.toml:
//   • androidx.compose.animation.*          (BOM 2024.10.00)
//   • androidx.compose.animation.core.*     (BOM 2024.10.00)
//   • androidx.compose.foundation.*         (BOM 2024.10.00)
//   • androidx.compose.material.icons.*     (material-icons-core — Close)
//   • androidx.compose.material3.*          (BOM 2024.10.00)
//   • androidx.compose.ui.*                 (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview
//
// String resources used (all present in res/values/strings.xml):
//   nowplaying_stream_inspector_title
//   nowplaying_stream_inspector_sample_rate
//   nowplaying_stream_inspector_audio_hal
//   nowplaying_stream_inspector_latency
//   nowplaying_stream_inspector_buffer
//   nowplaying_stream_inspector_bitperfect
//   nowplaying_stream_inspector_bitperfect_active
//   nowplaying_stream_inspector_bitperfect_bypassed
//   nowplaying_stream_inspector_close_cd
//   nowplaying_stream_latency_value_format
//   nowplaying_stream_buffer_value_format
//   dismiss

package com.futura.ui.components.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * Slide-down Stream Inspector modal.
 *
 * @param visible       Whether the modal is currently shown. Drives both
 *                      the scrim fade and the panel slide animation.
 * @param onDismiss     Invoked when the user taps the scrim or the close
 *                      button. Caller flips `visible` to `false`.
 * @param modifier      Optional outer modifier. The component fills the
 *                      available size when composed (regardless of
 *                      `visible`), so callers typically place it as the
 *                      last child of the Now Playing root Box to ensure
 *                      it renders on top of the main content.
 * @param sampleRateText  Value for the "Sample Rate" row. Default
 *                        corresponds to the placeholder format badge
 *                        ("96 kHz / 24-bit"). Becomes per-track once
 *                        NowPlayingViewModel is delivered.
 * @param audioHalText    Value for the "Audio HAL" row. Default "AAudio"
 *                        matches the app's Oboe/AAudio stack.
 * @param latencyMs       Numeric latency in milliseconds; formatted via
 *                        nowplaying_stream_latency_value_format.
 * @param bufferFrames    Numeric buffer size in frames; formatted via
 *                        nowplaying_stream_buffer_value_format.
 * @param isBitPerfect    Whether the DSP chain is currently bit-perfect.
 *                        Drives the ACTIVITY/BYPASSED pill colour and text.
 */
@Composable
fun StreamInspectorModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sampleRateText: String = "96 kHz / 24-bit",
    audioHalText: String = "AAudio",
    latencyMs: Float = 8.3f,
    bufferFrames: Int = 192,
    isBitPerfect: Boolean = true
) {
    Box(modifier = modifier.fillMaxSize()) {

        // ── Scrim ─────────────────────────────────────────────────────
        // Fades independently of the panel: the panel slides, the scrim
        // dims. Different easings keep the two motions from feeling
        // mechanical.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = fadeOut(animationSpec = tween(durationMillis = 200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FuturaColors.OLEDBlack.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(R.string.dismiss),
                        onClick = onDismiss
                    )
            )
        }

        // ── Panel ─────────────────────────────────────────────────────
        // Anchored to the top of the parent Box. Sliding from
        // `-contentHeight` to `0` makes the panel enter from above the
        // screen's top edge, exactly like a notification-shade pull.
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(
                    durationMillis = 280,
                    easing = FastOutSlowInEasing
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(
                    durationMillis = 220,
                    easing = FastOutSlowInEasing
                )
            ) + fadeOut(animationSpec = tween(durationMillis = 160)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Panel(
                sampleRateText = sampleRateText,
                audioHalText = audioHalText,
                latencyMs = latencyMs,
                bufferFrames = bufferFrames,
                isBitPerfect = isBitPerfect,
                onDismiss = onDismiss
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Panel (drawer body)
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun Panel(
    sampleRateText: String,
    audioHalText: String,
    latencyMs: Float,
    bufferFrames: Int,
    isBitPerfect: Boolean,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 24.dp,
                    bottomEnd = 24.dp
                )
            )
            .background(FuturaColors.SurfaceBase)
            .border(
                width = 1.dp,
                color = FuturaColors.Emerald.copy(alpha = 0.55f),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 24.dp,
                    bottomEnd = 24.dp
                )
            )
            // Consume taps on the panel's empty area so they don't fall
            // through to the scrim and accidentally dismiss. This runs
            // only when no child clickable (close button, etc.) claims
            // the tap.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { /* consume, do nothing */ }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
                )
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp)
        ) {
            HeaderRow(onDismiss = onDismiss)

            Spacer(Modifier.height(12.dp))

            HorizontalDivider()

            Spacer(Modifier.height(12.dp))

            DiagnosticRow(
                labelRes = R.string.nowplaying_stream_inspector_sample_rate,
                value = sampleRateText
            )
            DiagnosticRow(
                labelRes = R.string.nowplaying_stream_inspector_audio_hal,
                value = audioHalText
            )
            DiagnosticRow(
                labelRes = R.string.nowplaying_stream_inspector_latency,
                value = stringResource(
                    R.string.nowplaying_stream_latency_value_format,
                    latencyMs
                )
            )
            DiagnosticRow(
                labelRes = R.string.nowplaying_stream_inspector_buffer,
                value = stringResource(
                    R.string.nowplaying_stream_buffer_value_format,
                    bufferFrames
                )
            )
            BitPerfectRow(isBitPerfect = isBitPerfect)
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Header
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun HeaderRow(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.nowplaying_stream_inspector_title),
            color = FuturaColors.Emerald,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.nowplaying_stream_inspector_close_cd),
                tint = FuturaColors.TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun HorizontalDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FuturaColors.Emerald.copy(alpha = 0.2f))
    )
}

// ═════════════════════════════════════════════════════════════════════════
// Diagnostic rows
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun DiagnosticRow(
    labelRes: Int,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(labelRes).uppercase(),
            color = FuturaColors.TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = FuturaColors.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BitPerfectRow(isBitPerfect: Boolean) {
    val accent = if (isBitPerfect) FuturaColors.Emerald else FuturaColors.Amber
    val pillText = stringResource(
        if (isBitPerfect) R.string.nowplaying_stream_inspector_bitperfect_active
        else R.string.nowplaying_stream_inspector_bitperfect_bypassed
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.nowplaying_stream_inspector_bitperfect)
                .uppercase(),
            color = FuturaColors.TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.18f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = pillText,
                color = accent,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Previews
// ═════════════════════════════════════════════════════════════════════════

@Preview(
    name = "StreamInspectorModal — bit-perfect",
    showBackground = true,
    backgroundColor = 0xFF000000,
    heightDp = 400
)
@Composable
private fun StreamInspectorModalPreviewBitPerfect() {
    FuturaTheme {
        StreamInspectorModal(
            visible = true,
            onDismiss = {}
        )
    }
}

@Preview(
    name = "StreamInspectorModal — bypassed",
    showBackground = true,
    backgroundColor = 0xFF000000,
    heightDp = 400
)
@Composable
private fun StreamInspectorModalPreviewBypassed() {
    FuturaTheme {
        StreamInspectorModal(
            visible = true,
            onDismiss = {},
            sampleRateText = "48 kHz / 16-bit",
            audioHalText = "OpenSL ES",
            latencyMs = 22.6f,
            bufferFrames = 512,
            isBitPerfect = false
        )
    }
}