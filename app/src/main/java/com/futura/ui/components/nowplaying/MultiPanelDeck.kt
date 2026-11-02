// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/MultiPanelDeck.kt
//
// Phase 2 — Now Playing multi-panel deck.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The horizontal tab-switcher + content area that sits below the track
// identity strip on the Now Playing screen. Per the Phase 2 design spec:
//
//     "Swappable Multi-Panel Deck (Under Artwork):
//        Horizontal tab switcher: ['UP NEXT QUEUE', 'SYNCED LYRICS',
//        'RELATED & MOOD']."
//
// Delivers:
//   • A horizontal tab switcher with three tabs and an animated
//     emerald selection background.
//   • A recessed content card that swaps its body when the tab changes.
//   • Internal tab state (three tabs, none of which the parent needs to
//     control — the user picks a tab, the deck renders it).
//
// ── Content seams (no forward references) ────────────────────────────────
// The three actual panel bodies live in sibling files that have not been
// delivered yet:
//
//     UpNextPanel.kt
//     LyricsPanel.kt
//     RelatedPanel.kt
//
// Importing them would violate the "no forward references" rule. Instead,
// MultiPanelDeck exposes each panel as a `@Composable () -> Unit` slot
// with a built-in empty-state default. The behaviour is:
//
//     Today:
//         MultiPanelDeck(modifier = Modifier.weight(1f))
//       → Empty states render under each tab. Fits the current
//         NowPlayingScreen stub perfectly.
//
//     After UpNextPanel.kt / LyricsPanel.kt / RelatedPanel.kt land:
//         MultiPanelDeck(
//             modifier = Modifier.weight(1f),
//             upNextContent  = { UpNextPanel(...) },
//             lyricsContent  = { LyricsPanel(...) },
//             relatedContent = { RelatedPanel(...) }
//         )
//       → Real panels wire in with a three-line change at the callsite.
//         This file does not need any edits.
//
// The default empty-state content is implemented inline (private
// `EmptyPanelContent` composable) so the deck is fully usable today —
// it renders a centred monospace hint inside the recessed card for each
// tab, using the empty-state strings already in strings.xml.
//
// ── Height contract ───────────────────────────────────────────────────────
// MultiPanelDeck is designed to expand into the space its parent gives
// it: the content card uses `Modifier.weight(1f)` internally, so the
// deck **must be composed with a bounded height**. The standard way to
// satisfy that is to give the deck's outer `modifier` a
// `Modifier.weight(...)` inside NowPlayingScreen's Column, or a fixed
// `Modifier.height(...)` if used standalone. Without a bounded height
// the content card collapses to zero — this is standard Compose
// behaviour for `weight` inside an unbounded parent and is documented
// here so callers do not trip over it.
//
// ── Visual language ──────────────────────────────────────────────────────
//   • Tab switcher: dark glassmorphic strip (SurfaceBase at 40% alpha)
//     with three equal-width tabs. The selected tab has an animated
//     emerald background (18% alpha) and emerald text; unselected tabs
//     have tertiary text and no background.
//   • Content card: recessed surface (`SurfaceRecessed` at 60% alpha)
//     with a 1dp `SurfaceRaised` border and 16dp rounded corners —
//     identical to the previous NowPlayingScreen stub's content card,
//     so the visual handoff from stub to component is invisible.
//   • Monospace ALL-CAPS typography on both the tab labels and the
//     empty-state hints, matching every other metadata strip in the
//     app.
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
//   nowplaying_tab_upnext
//   nowplaying_tab_lyrics
//   nowplaying_tab_related
//   nowplaying_queue_empty
//   nowplaying_lyrics_empty
//   nowplaying_related_empty
//
// No future project class or file is referenced.

package com.futura.ui.components.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
// Tab catalogue
// ═════════════════════════════════════════════════════════════════════════

/**
 * Number of tabs in the deck. Kept as a named constant so the
 * `initialTab` coercion below stays in sync if the catalogue ever grows.
 */
private const val TAB_COUNT = 3

/**
 * Tab labels in display order. Index 0 = Up Next, 1 = Lyrics, 2 =
 * Related & Mood — matching the design spec's tab list verbatim.
 */
private val TAB_LABEL_RES = intArrayOf(
    R.string.nowplaying_tab_upnext,
    R.string.nowplaying_tab_lyrics,
    R.string.nowplaying_tab_related
)

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Swappable multi-panel deck for the Now Playing screen.
 *
 * @param modifier Modifier applied to the outer Column. **Must be given
 *        a bounded height** — typically `Modifier.weight(1f)` inside
 *        NowPlayingScreen's Column — because the content card uses
 *        `weight(1f)` internally to fill the remaining vertical space.
 *        See the file header's "Height contract" section.
 * @param initialTab Tab index to select on first composition. Defaults
 *        to 0 (Up Next). Coerced into `[0, TAB_COUNT)`. Because the tab
 *        state is internal, changing `initialTab` after first
 *        composition has no effect — pass a different value with a
 *        different `key` at the callsite to force a reset.
 * @param upNextContent Body of the "UP NEXT QUEUE" tab. Defaults to an
 *        empty-state hint. Override with `{ UpNextPanel(...) }` once
 *        `ui/components/nowplaying/UpNextPanel.kt` is delivered.
 * @param lyricsContent Body of the "SYNCED LYRICS" tab. Defaults to an
 *        empty-state hint. Override with `{ LyricsPanel(...) }` once
 *        `ui/components/nowplaying/LyricsPanel.kt` is delivered.
 * @param relatedContent Body of the "RELATED & MOOD" tab. Defaults to
 *        an empty-state hint. Override with `{ RelatedPanel(...) }` once
 *        `ui/components/nowplaying/RelatedPanel.kt` is delivered.
 */
@Composable
fun MultiPanelDeck(
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    upNextContent: @Composable () -> Unit = {
        EmptyPanelContent(R.string.nowplaying_queue_empty)
    },
    lyricsContent: @Composable () -> Unit = {
        EmptyPanelContent(R.string.nowplaying_lyrics_empty)
    },
    relatedContent: @Composable () -> Unit = {
        EmptyPanelContent(R.string.nowplaying_related_empty)
    }
) {
    var selectedTabIndex by remember {
        mutableIntStateOf(initialTab.coerceIn(0, TAB_COUNT - 1))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TabSwitcher(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it }
        )

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(FuturaColors.SurfaceRecessed.copy(alpha = 0.6f))
                .border(
                    width = 1.dp,
                    color = FuturaColors.SurfaceRaised,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            // selectedTabIndex is always 0..2 (bounded by initialTab
            // coercion and by the tab-switcher click range). The `else`
            // branch exists only to satisfy Kotlin's exhaustive-when
            // preference for expression position; it is unreachable.
            when (selectedTabIndex) {
                0 -> upNextContent()
                1 -> lyricsContent()
                else -> relatedContent()
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Tab switcher
// ═════════════════════════════════════════════════════════════════════════

/**
 * Horizontal three-tab switcher with an animated emerald background on
 * the selected tab.
 *
 * Uses `Modifier.selectable(role = Role.Tab)` on each tab so TalkBack
 * announces "tab, [label], [selected]" and so the whole tab row is
 * navigable as a single tab group — the standard Material 3 accessibility
 * pattern, and it also gives keyboard users arrow-key navigation for
 * free.
 */
@Composable
private fun TabSwitcher(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.4f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TAB_LABEL_RES.forEachIndexed { index, labelRes ->
            val isSelected = index == selectedTabIndex

            // Short 220 ms tween keeps the background change smooth
            // without feeling laggy on rapid tab-switching.
            val selectionAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = tween(durationMillis = 220),
                label = "deck-tab-alpha-$index"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        FuturaColors.Emerald.copy(alpha = 0.18f * selectionAlpha)
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onTabSelected(index) }
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(labelRes),
                    color = if (isSelected) FuturaColors.Emerald
                            else FuturaColors.TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Empty-state content
// ═════════════════════════════════════════════════════════════════════════
//
// Default slot content for each tab until the real panels land. Centred
// monospace hint inside the recessed card. Uses `fillMaxSize()` so it
// occupies the entire content area regardless of message length.

@Composable
private fun EmptyPanelContent(messageRes: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(messageRes),
            color = FuturaColors.TextTertiary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Previews
// ═════════════════════════════════════════════════════════════════════════
//
// Three previews: one per default tab state, showing that the deck
// renders the same content area regardless of which tab is active. All
// use the built-in empty states — the previews will keep working
// unchanged once the real panels land (defaults still exist).

@Preview(
    name = "MultiPanelDeck — Up Next (default)",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 260
)
@Composable
private fun MultiPanelDeckPreviewUpNext() {
    FuturaTheme {
        MultiPanelDeck(
            modifier = Modifier.padding(16.dp),
            initialTab = 0
        )
    }
}

@Preview(
    name = "MultiPanelDeck — Lyrics",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 260
)
@Composable
private fun MultiPanelDeckPreviewLyrics() {
    FuturaTheme {
        MultiPanelDeck(
            modifier = Modifier.padding(16.dp),
            initialTab = 1
        )
    }
}

@Preview(
    name = "MultiPanelDeck — Related & Mood",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 260
)
@Composable
private fun MultiPanelDeckPreviewRelated() {
    FuturaTheme {
        MultiPanelDeck(
            modifier = Modifier.padding(16.dp),
            initialTab = 2
        )
    }
}