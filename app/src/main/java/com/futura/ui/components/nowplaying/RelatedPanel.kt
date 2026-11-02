// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/RelatedPanel.kt
//
// Phase 2 — Now Playing "Related & Mood" panel.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The third of the three swappable tab bodies inside MultiPanelDeck. Per
// the Phase 2 design spec:
//
//     "Panel 3 (Related): RELATED & MOOD"
//
// Combined with the Phase 5 ML capabilities this panel will eventually
// surface two distinct blocks:
//
//   1. MOOD PROFILE — a row of mood chips summarising the currently
//      playing track's classified moods. Populated by the future
//      `ml/ContextMoodEngine.kt` / `ml/AutoTaggingClassifier.kt`
//      (Phase 5). Until then, callers pass a set of `MoodTag`s and the
//      panel renders them; passing an empty set renders nothing.
//
//   2. SIMILAR TRACKS — acoustically similar tracks ranked by cosine
//      similarity over the local vector database
//      (`engine/ml/LocalVectorDatabase.h`, `AcousticSimilarityEngine.h`
//      in Phase 5). Until then, callers pass a list of `UpNextItem`s
//      (the same UI model used by UpNextPanel) and the panel renders
//      them as tappable discovery rows.
//
// ── Component boundary ────────────────────────────────────────────────────
// `MoodTag` is a UI-only enum declared in this file. It is deliberately
// **not** the eventual enum/struct produced by the ML layer — that layer
// is free to define its own taxonomy. Mapping from the ML output to this
// UI enum happens at the callsite (NowPlayingScreen / NowPlayingViewModel)
// once the ML classes are delivered.
//
// Related-track data reuses `UpNextItem` from `UpNextPanel.kt` (same
// package). Sharing the model between the two panels is deliberate — both
// surfaces render "a list of tracks the user might play next", and
// splitting into two nearly-identical data classes would create
// unnecessary mapping code at every callsite.
//
// All state is caller-owned: `relatedTracks`, `currentMoods`, and the
// navigation decisions via `onRelatedTrackClick`. The panel itself has no
// internal state beyond scroll position.
//
// ── Section header strings ───────────────────────────────────────────────
// The two section sub-headers ("SIMILAR TRACKS", "MOOD PROFILE") are
// currently English literals exposed as `relatedTracksHeaderText` and
// `moodHeaderText` parameters with those literals as defaults. Proper
// string resources (`nowplaying_related_tracks_header`,
// `nowplaying_mood_header`) are not yet in `res/values/strings.xml`.
// When they are added, callers pass them in via `stringResource(...)`;
// this file requires no change at that point.
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                      (generated from delivered resources)
//   • com.futura.ui.theme.FuturaColors  (delivered in theme/Theme.kt)
//   • com.futura.ui.theme.FuturaTheme   (delivered, Preview only)
//   • com.futura.ui.components.nowplaying.UpNextItem
//                                       (public, delivered in UpNextPanel.kt —
//                                        same package, so no import statement
//                                        is required, but the type IS used)
//
// All other imports resolve to artifacts declared in
// gradle/libs.versions.toml:
//   • androidx.compose.foundation.*          (BOM 2024.10.00)
//   • androidx.compose.foundation.layout.FlowRow
//                                            (BOM 2024.10.00, @ExperimentalLayoutApi)
//   • androidx.compose.material.icons.*      (material-icons-core)
//   • androidx.compose.material3.*           (BOM 2024.10.00)
//   • androidx.compose.ui.*                  (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview
//
// String resources used (all present in res/values/strings.xml):
//   nowplaying_tab_related
//   library_mood_quadrant_calm
//   library_mood_quadrant_happy
//   library_mood_quadrant_energetic
//   library_mood_quadrant_intense
//   nowplaying_related_empty
//
// Color resources used (all present in res/values/colors.xml):
//   library_mood_calm
//   library_mood_happy
//   library_mood_energetic
//   library_mood_intense

package com.futura.ui.components.nowplaying

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
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
// Public data model
// ═════════════════════════════════════════════════════════════════════════

/**
 * UI-level mood tag. Maps one-to-one with the four mood-map quadrants
 * already declared in `res/values/colors.xml` and `res/values/strings.xml`,
 * so the chip colour and label here match what the Phase 3 Library's
 * 2D Mood Map view will render for the same quadrant.
 *
 * `@StringRes` / `@ColorRes` are attached to the enum constants so callers
 * cannot accidentally pass a non-string / non-color integer when they
 * build their own UI from the enum.
 */
enum class MoodTag(
    @StringRes val labelRes: Int,
    @ColorRes val colorRes: Int
) {
    CALM(
        labelRes = R.string.library_mood_quadrant_calm,
        colorRes = R.color.library_mood_calm
    ),
    HAPPY(
        labelRes = R.string.library_mood_quadrant_happy,
        colorRes = R.color.library_mood_happy
    ),
    ENERGETIC(
        labelRes = R.string.library_mood_quadrant_energetic,
        colorRes = R.color.library_mood_energetic
    ),
    INTENSE(
        labelRes = R.string.library_mood_quadrant_intense,
        colorRes = R.color.library_mood_intense
    )
}

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Related & Mood tab body.
 *
 * @param relatedTracks Acoustically similar tracks to surface below the
 *        mood section. Reuses `UpNextItem` from the same package — see
 *        the file header's "Component boundary" note.
 * @param currentMoods Moods classified for the currently playing track.
 *        Rendered as a `FlowRow` of coloured chips in [MoodTag] ordinal
 *        order. Empty set → no mood section rendered.
 * @param onRelatedTrackClick Fired when the user taps a related-track
 *        row. The caller decides what happens (play, add to queue,
 *        open track info, …). The panel intentionally offers no
 *        pre-baked action button so the semantic stays flexible.
 * @param modifier Optional outer modifier. The panel fills the space it
 *        is given and manages its own vertical scrolling.
 * @param relatedTracksHeaderText Section header shown above the related
 *        tracks list. Defaults to the English literal "SIMILAR TRACKS"
 *        because no matching resource exists yet (see file header).
 * @param moodHeaderText Section header shown above the mood chips.
 *        Defaults to the English literal "MOOD PROFILE" for the same
 *        reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelatedPanel(
    relatedTracks: List<UpNextItem> = emptyList(),
    currentMoods: Set<MoodTag> = emptySet(),
    onRelatedTrackClick: (UpNextItem) -> Unit = {},
    modifier: Modifier = Modifier,
    relatedTracksHeaderText: String = "SIMILAR TRACKS",
    moodHeaderText: String = "MOOD PROFILE"
) {
    Column(modifier = modifier.fillMaxSize()) {

        HeaderRow(relatedCount = relatedTracks.size)

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = FuturaColors.Emerald.copy(alpha = 0.15f)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            // ── Mood section ────────────────────────────────────────────
            if (currentMoods.isNotEmpty()) {
                SectionHeader(text = moodHeaderText)
                Spacer(Modifier.height(6.dp))
                MoodChipRow(moods = currentMoods)
                Spacer(Modifier.height(14.dp))
            }

            // ── Related tracks section ──────────────────────────────────
            if (relatedTracks.isNotEmpty()) {
                SectionHeader(text = relatedTracksHeaderText)
                Spacer(Modifier.height(4.dp))
                relatedTracks.forEach { track ->
                    RelatedTrackRow(
                        item = track,
                        onClick = { onRelatedTrackClick(track) }
                    )
                }
            }

            // ── Empty state ─────────────────────────────────────────────
            if (currentMoods.isEmpty() && relatedTracks.isEmpty()) {
                EmptyState()
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Top header (tab label + count chip)
// ═════════════════════════════════════════════════════════════════════════
//
// Matches the UpNextPanel header pattern: monospace ALL-CAPS tab label
// on the left, small count chip on the right. The count refers to the
// number of related tracks (the most useful quantity for at-a-glance
// scanning; mood count is typically 1–4 and always visible as chips).

@Composable
private fun HeaderRow(relatedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.nowplaying_tab_related),
            color = FuturaColors.Emerald,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        CountChip(count = relatedCount)
    }
}

@Composable
private fun CountChip(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(FuturaColors.Emerald.copy(alpha = 0.18f))
            .border(
                width = 1.dp,
                color = FuturaColors.Emerald.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = count.toString().padStart(2, '0'),
            color = FuturaColors.Emerald,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Section header
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            color = FuturaColors.Cyan.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Mood chips row
// ═════════════════════════════════════════════════════════════════════════
//
// FlowRow wraps naturally when more chips are supplied than fit on one
// line — important if a future caller offers richer mood taxonomies
// beyond the four quadrant constants.
//
// Chips are informational, not interactive: tapping does nothing.
// When a "filter by mood" flow is introduced, this becomes clickable via
// an `onMoodClick` parameter — a signature extension, no behavioural
// rework.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodChipRow(moods: Set<MoodTag>) {
    // Sort by ordinal for stable display order (CALM, HAPPY, ENERGETIC,
    // INTENSE) regardless of the order the caller's set iterates in.
    val sortedMoods = moods.sorted()

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sortedMoods.forEach { mood ->
            MoodChip(mood = mood)
        }
    }
}

@Composable
private fun MoodChip(mood: MoodTag) {
    val accent = colorResource(mood.colorRes)
    val label = stringResource(mood.labelRes)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = accent,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            maxLines = 1
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Related-track row
// ═════════════════════════════════════════════════════════════════════════
//
// Tappable track row: title (white), artist (cyan), duration (mono,
// tertiary), and a subtle keyboard-arrow-right trailing glyph to signal
// "tap for more". Reuses the trailing arrow icon (rather than the "add"
// chip that UpNextPanel's similar rows use) because the caller's
// `onRelatedTrackClick` handles the semantic — this panel does not
// presuppose "add to queue" as the only action.

@Composable
private fun RelatedTrackRow(
    item: UpNextItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = FuturaColors.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.artist,
                color = FuturaColors.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = formatTrackDuration(item.durationSeconds),
            color = FuturaColors.TextTertiary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )

        Spacer(Modifier.width(6.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null, // row click carries the action
            tint = FuturaColors.TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Empty state
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.nowplaying_related_empty),
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
// Duration formatting
// ═════════════════════════════════════════════════════════════════════════
//
// Same format used in UpNextPanel so the two panels are visually
// consistent. `--:--` for missing duration.

private fun formatTrackDuration(seconds: Long): String {
    if (seconds <= 0L) return "--:--"
    val h = seconds / 3600L
    val m = (seconds % 3600L) / 60L
    val s = seconds % 60L
    return if (h > 0L) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Previews
// ═════════════════════════════════════════════════════════════════════════
//
// Three previews:
//   • Full — moods + related tracks, the happy path.
//   • Moods only — no related tracks supplied; only the mood section
//     renders.
//   • Empty — both sections hidden, empty-state message shown.

@Preview(
    name = "RelatedPanel — full",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 400
)
@Composable
private fun RelatedPanelPreviewFull() {
    FuturaTheme {
        RelatedPanel(
            currentMoods = setOf(
                MoodTag.ENERGETIC,
                MoodTag.HAPPY,
                MoodTag.CALM
            ),
            relatedTracks = listOf(
                UpNextItem("r1", "Cold Circuit", "VOID KISS", 189L),
                UpNextItem("r2", "Hollow Vector", "GHOSTLINE", 243L),
                UpNextItem("r3", "Signal Decay", "SYNTH DIVISION", 215L),
                UpNextItem("r4", "Neon Rain (Reprise)", "VOID KISS", 176L)
            ),
            onRelatedTrackClick = {}
        )
    }
}

@Preview(
    name = "RelatedPanel — moods only",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 260
)
@Composable
private fun RelatedPanelPreviewMoodsOnly() {
    FuturaTheme {
        RelatedPanel(
            currentMoods = setOf(MoodTag.INTENSE, MoodTag.ENERGETIC),
            relatedTracks = emptyList(),
            onRelatedTrackClick = {}
        )
    }
}

@Preview(
    name = "RelatedPanel — empty",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 240
)
@Composable
private fun RelatedPanelPreviewEmpty() {
    FuturaTheme {
        RelatedPanel(
            currentMoods = emptySet(),
            relatedTracks = emptyList(),
            onRelatedTrackClick = {}
        )
    }
}