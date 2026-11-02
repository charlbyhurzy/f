// futura-audio/app/src/main/java/com/futura/ui/screens/LoadingScreen.kt
//
// Splash fallback screen shown while MainViewModel.isReady is false.
// Per the project tree this screen is fully implemented (✅), unlike
// the Phase 2–6 screen stubs.
//
// ── Responsibilities ──────────────────────────────────────────────────────
//   1. Observe MainViewModel.phase and render the correct visual state:
//        BootstrapPhase.Loading  → animated ring + "INITIALIZING AUDIO ENGINE…"
//        BootstrapPhase.Ready    → brief "READY" flash, then onReady() navigates
//        BootstrapPhase.Failed   → warning glyph + reason + Retry button
//   2. Call onReady() the moment isReady flips true, which MainActivity's
//      NavHost uses to pop the Loading route and enter the main flow.
//   3. Handle its own viewModel via Hilt. Because the NavHost scopes the
//      Loading route to its own NavBackStackEntry, this ViewModel lives
//      only as long as Loading is on the stack — which is exactly right,
//      since its only job is bootstrap gating.
//
// ── Signature compatibility ──────────────────────────────────────────────
// Current MainActivity calls `LoadingPlaceholder(onReady = { … })`. When
// this screen is wired in (a one-line swap in MainActivity, not part of
// this delivery), that call becomes `LoadingScreen(onReady = { … })` —
// identical signature, no changes needed at the callsite.
//
// ── Design language ──────────────────────────────────────────────────────
// • OLED true black backdrop (#000000) — same as every other surface.
// • Emerald (#00FF66) breathing ring — the app's primary accent, matching
//   the launcher foreground vector and the Wake-Up Overlay clock glow.
// • Monospace ALL-CAPS status line — matches WakeUpOverlayScreen's
//   "ALARM ARMED // 07:00 AM" typography and the "cybernetic console"
//   voice used throughout the app.
// • No glassmorphic card on this screen. Loading is deliberately
//   flatter than the main flow — nothing competes with the breathing
//   ring while the user is waiting.
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imports resolve to:
//   • androidx.compose.animation.core.*      (BOM 2024.10.00)
//   • androidx.compose.foundation.*          (BOM 2024.10.00)
//   • androidx.compose.material3.*           (BOM 2024.10.00)
//   • androidx.compose.runtime.*             (BOM 2024.10.00)
//   • androidx.compose.ui.*                  (BOM 2024.10.00)
//   • androidx.hilt.navigation.compose.hiltViewModel
//                                            (libs.versions.toml: hilt-navigation-compose)
//   • androidx.lifecycle.compose.collectAsStateWithLifecycle
//                                            (libs.versions.toml: lifecycle)
//   • com.futura.R                           (generated from res/values/strings.xml)
//   • com.futura.ui.MainViewModel            (delivered)
//   • com.futura.ui.MainViewModel.BootstrapPhase
//                                            (nested in MainViewModel)
//   • com.futura.ui.theme.FuturaColors       (delivered in theme/Theme.kt)
// No project class beyond the above is referenced.

package com.futura.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futura.R
import com.futura.ui.MainViewModel
import com.futura.ui.MainViewModel.BootstrapPhase
import com.futura.ui.theme.FuturaColors

// ═════════════════════════════════════════════════════════════════════════
// Public entry point
// ═════════════════════════════════════════════════════════════════════════

/**
 * Full-screen splash fallback.
 *
 * @param onReady Invoked exactly once, the moment [MainViewModel.isReady]
 *                becomes `true`. MainActivity's NavHost uses this callback
 *                to pop the Loading route and navigate to MainRoute.START.
 * @param modifier Optional outer modifier from the callsite.
 * @param viewModel Hilt-provided MainViewModel. Default argument makes the
 *                screen trivial to test by supplying a stub instance.
 */
@Composable
fun LoadingScreen(
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val isReady by viewModel.isReady.collectAsStateWithLifecycle()

    // Single source of truth for the "advance to main flow" transition.
    // Fires once on `false → true`, and again if a retry cycle re-runs
    // the bootstrap. That is deliberate: a successful retry should
    // navigate just like a successful first attempt.
    LaunchedEffect(isReady) {
        if (isReady) onReady()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack)
    ) {
        when (val currentPhase = phase) {
            is BootstrapPhase.Loading -> LoadingContent()
            is BootstrapPhase.Ready   -> ReadyContent()
            is BootstrapPhase.Failed  -> FailedContent(
                reason = currentPhase.reason,
                onRetry = viewModel::retryBootstrap
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Loading state
// ═════════════════════════════════════════════════════════════════════════

/**
 * Centred ring pulse + app name + monospace status line.
 *
 * The ring is drawn from a single [Canvas] for pixel-precise sizing:
 * three concentric strokes (pulsing outer, faint middle, solid inner)
 * around a small filled pivot dot. The outer ring animates its radius
 * and alpha over two seconds — a "sonar" pulse that fits the alarm/
 * cybernetic aesthetic without ever competing with the status text.
 */
@Composable
private fun LoadingContent() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading-pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Restart
        ),
        label = "loading-pulse-value"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LoadingRing(pulse = pulse)

        Spacer(Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.app_name).uppercase(),
            color = FuturaColors.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 4.sp
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.loading_engine).uppercase(),
            color = FuturaColors.Emerald,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Three concentric rings + pivot dot. The `pulse` parameter (0f..1f) drives
 * the outer ring's radius and fade; the inner rings are static.
 */
@Composable
private fun LoadingRing(pulse: Float) {
    Canvas(modifier = Modifier.size(120.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.35f

        // Outer pulse ring — grows and fades over each cycle.
        drawCircle(
            color = FuturaColors.Emerald.copy(alpha = 1f - pulse),
            radius = baseRadius * (1f + pulse * 0.55f),
            center = center,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Middle ring — static, faint.
        drawCircle(
            color = FuturaColors.Emerald.copy(alpha = 0.35f),
            radius = baseRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        // Inner ring — static, solid emerald.
        drawCircle(
            color = FuturaColors.Emerald,
            radius = baseRadius * 0.55f,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Pivot dot.
        drawCircle(
            color = FuturaColors.Emerald,
            radius = baseRadius * 0.12f,
            center = center
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Ready state (transient — onReady() fires within the same frame)
// ═════════════════════════════════════════════════════════════════════════

/**
 * Brief success flash. Rendered for at most one frame between the
 * `isReady → true` transition and the `onReady()` callback that navigates
 * away. Kept visually quiet so it never reads as a distinct screen — it
 * exists only so the Loading route doesn't flash a blank frame during
 * navigation setup.
 */
@Composable
private fun ReadyContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "READY",
            color = FuturaColors.Emerald,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Failed state
// ═════════════════════════════════════════════════════════════════════════

/**
 * Warning glyph + headline + reason + retry button.
 *
 * The retry button uses the emerald container / true-black content pair
 * so it reads as the primary action on an otherwise red-accented screen —
 * matching the design's rule that emerald is the "do the thing" colour
 * even when the surrounding context is a failure state.
 */
@Composable
private fun FailedContent(reason: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WarningGlyph()

        Spacer(Modifier.height(24.dp))

        Text(
            text = "BOOT FAILED",
            color = FuturaColors.Error,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 3.sp
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = reason,
            color = FuturaColors.TextTertiary,
            fontSize = 13.sp,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = FuturaColors.Emerald,
                contentColor = FuturaColors.OLEDBlack
            )
        ) {
            Text(
                text = stringResource(R.string.retry).uppercase(),
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
    }
}

/**
 * Circular warning glyph — hollow error-red ring with an exclamation mark.
 *
 * Drawn inline rather than using `Icons.Filled.Warning` because the circle
 * + exclamation geometry echoes the Loading ring's silhouette, giving the
 * two states a shared visual family: same shape language, different colour
 * and content.
 */
@Composable
private fun WarningGlyph() {
    Canvas(modifier = Modifier.size(72.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.42f

        // Outer ring.
        drawCircle(
            color = FuturaColors.Error,
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Exclamation stem.
        drawLine(
            color = FuturaColors.Error,
            start = Offset(center.x, center.y - radius * 0.5f),
            end = Offset(center.x, center.y + radius * 0.15f),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Exclamation dot.
        drawCircle(
            color = FuturaColors.Error,
            radius = 2.5.dp.toPx(),
            center = Offset(center.x, center.y + radius * 0.55f)
        )
    }
}