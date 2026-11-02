// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/UpNextPanel.kt
//
// Phase 2 — Now Playing "Up Next" panel.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The first of the three swappable tab bodies inside MultiPanelDeck.
// Per the Phase 2 design spec:
//
//     "Panel 1 (Up Next): Reorderable list with drag handles and
//      'Similar Track Suggestions'."
//
// Renders:
//
//   1. Header strip — "UP NEXT" label + a count chip showing how many
//      tracks are queued.
//   2. The queue itself — one row per track (index, title, artist,
//      duration), with a drag handle on the trailing edge of each row.
//      Dragging a handle moves the row up or down; on release the row
//      commits to its new position via `onReorder(from, to)`.
//   3. A "SIMILAR TRACK SUGGESTIONS" section beneath the queue, with
//      one muted row per suggestion and an add glyph. Tapping a
//      suggestion fires `onSimilarClick`, giving the caller the option
//      to append it to the queue.
//
// ── Component boundary ────────────────────────────────────────────────────
// `UpNextItem` is a UI-only value object declared in this file. It
// carries just what the panel needs to render — id, title, artist,
// duration — and is deliberately **not** the eventual `TrackEntity`
// from Room. The `NowPlayingViewModel` (when delivered) will map
// `TrackEntity` → `UpNextItem` at the callsite, keeping the UI layer
// decoupled from the persistence layer.
//
// All state that survives the panel's lifetime lives in the caller:
//
//   • `items`               → owned by caller (NowPlayingViewModel will own it)
//   • `similarSuggestions`  → owned by caller
//   • reorder decisions     → caller via `onReorder`
//   • item selection        → caller via `onItemClick`
//   • suggestion selection  → caller via `onSimilarClick`
//
// The only internal state is transient drag bookkeeping (offset, isDragging),
// keyed by `item.id` so it survives list reorders cleanly.
//
// ── Drag-to-reorder: implementation notes ────────────────────────────────
// Full LazyColumn drag-to-reorder normally requires a `ReorderableLazyList`
// library, which this project does not depend on. Instead, the panel uses
// a straightforward Column + verticalScroll implementation:
//
//   • Drag gesture is attached only to the drag-handle icon, so vertical
//     scrolling of the whole panel still works as expected.
//   • The dragged row translates vertically with the finger.
//   • On drag release, the target index is computed from
//     `round(accumulatedDrag / rowHeightPx)` and, if different from the
//     source index, `onReorder(source, target)` is fired once.
//   • The caller is expected to apply the reorder to its `items` list;
//     the panel then re-renders with the new order.
//
// This is intentionally simpler than a full drag reorder with live
// neighbour animation. It produces the expected UX (drag a handle, row
// moves, list commits on release) for typical queue sizes (≤ 30 tracks),
// compiles with zero extra dependencies, and can be upgraded to
// LazyColumn + neighbour translation in a future turn without changing
// this file's public signature.
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
//   • androidx.compose.material.icons.*      (material-icons-core / -extended)
//   • androidx.compose.material3.*           (BOM 2024.10.00)
//   • androidx.compose.ui.*                  (BOM 2024.10.00)
//   • androidx.compose.ui.tooling.preview.Preview
//
// String resources used (all present in res/values/strings.xml):
//   nowplaying_tab_upnext
//   nowplaying_queue_empty
//   nowplaying_drag_handle_cd
//
// No future project class or file is referenced.

package com.futura.ui.components.nowplaying

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.R
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme
import kotlin.math.roundToInt

// ═════════════════════════════════════════════════════════════════════════
// Public data model
// ═════════════════════════════════════════════════════════════════════════

/**
 * UI-level representation of a single queued track.
 *
 * Deliberately narrow: only the fields the panel needs to render. The
 * caller is responsible for mapping from the eventual `TrackEntity`
 * (Room) or any other source into this shape. `id` must be stable and
 * unique within the queue — the drag state keys on it.
 *
 * @param id Stable identifier for the track. Usually the database
 *        primary key stringified, but any stable `String` works.
 * @param title Track title.
 * @param artist Display artist (pre-joined for multi-artist tracks).
 * @param durationSeconds Track length in whole seconds. `0L` renders
 *        as `"--:--"` in the duration column.
 */
data class UpNextItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long = 0L
)

// ═════════════════════════════════════════════════════════════════════════
// Layout constants
// ═════════════════════════════════════════════════════════════════════════

private val QueueRowHeight = 64.dp
private val SimilarRowHeight = 56.dp

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Up Next tab body — queue + similar-track suggestions.
 *
 * @param items           The current queue, in playback order.
 * @param onReorder       Fired once on drag release when the row lands
 *                        at a different index. Caller updates its
 *                        `items` list accordingly.
 * @param onItemClick     Fired when the user taps a queue row (usually
 *                        to jump to that track).
 * @param onSimilarClick  Fired when the user taps a similar-track
 *                        suggestion row (usually to append it to the
 *                        queue).
 * @param modifier        Optional outer modifier. The panel itself
 *                        fills the space it is given and manages its
 *                        own vertical scrolling — the caller only
 *                        needs to provide a bounded height (typically
 *                        via `Modifier.weight(1f)` inside
 *                        MultiPanelDeck's content card, which is
 *                        already the case).
 * @param similarSuggestions Read-only list of suggestions rendered
 *                        below the queue. When empty, the entire
 *                        similar section is hidden.
 */
@Composable
fun UpNextPanel(
    items: List<UpNextItem>,
    onReorder: (from: Int, to: Int) -> Unit,
    onItemClick: (UpNextItem) -> Unit,
    onSimilarClick: (UpNextItem) -> Unit,
    modifier: Modifier = Modifier,
    similarSuggestions: List<UpNextItem> = emptyList()
) {
    Column(modifier = modifier.fillMaxSize()) {

        HeaderRow(itemCount = items.size)

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
        ) {
            if (items.isEmpty()) {
                EmptyQueueState()
            } else {
                items.forEachIndexed { index, item ->
                    UpNextRow(
                        item = item,
                        index = index,
                        itemCount = items.size,
                        onMove = onReorder,
                        onClick = { onItemClick(item) }
                    )
                }
            }

            if (similarSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SimilarSectionHeader()
                similarSuggestions.forEach { suggestion ->
                    SimilarRow(
                        item = suggestion,
                        onClick = { onSimilarClick(suggestion) }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Header
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun HeaderRow(itemCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.nowplaying_tab_upnext),
            color = FuturaColors.Emerald,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        CountChip(count = itemCount)
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
// Queue row
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun UpNextRow(
    item: UpNextItem,
    index: Int,
    itemCount: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { QueueRowHeight.toPx() }

    // The pointerInput closure captures `index` at creation time, but we
    // need the *live* index after reorders. `rememberUpdatedState` gives
    // us a ref that updates on every recomposition, so the drag gesture
    // always sees the row's actual current position.
    val currentIndex by rememberUpdatedState(index)
    val currentItemCount by rememberUpdatedState(itemCount)

    // Transient drag state, keyed by item.id so it survives reorders of
    // the same logical row.
    var dragOffsetPx by remember(item.id) { mutableFloatStateOf(0f) }
    var isDragging by remember(item.id) { mutableStateOf(false) }

    // Visual offset. snap() while dragging means the row follows the
    // finger 1:1; spring() on release snaps it back to 0 with a small
    // bounce, unless the row moved to a new position (in which case the
    // list itself produces the visual change and dragOffset is reset
    // immediately in onDragEnd).
    val displayedOffsetPx by animateFloatAsState(
        targetValue = if (isDragging) dragOffsetPx else 0f,
        animationSpec = if (isDragging) {
            snap()
        } else {
            spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioLowBouncy
            )
        },
        label = "up-next-row-offset-${item.id}"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .offset { IntOffset(0, displayedOffsetPx.roundToInt()) }
            .background(
                if (isDragging) {
                    FuturaColors.SurfaceRaised.copy(alpha = 0.7f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track index — monospace, tertiary, fixed width so titles align
        // across rows with different index digits.
        Text(
            text = (currentIndex + 1).toString().padStart(2, '0'),
            color = FuturaColors.TextTertiary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(24.dp)
        )

        Spacer(Modifier.width(8.dp))

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

        Spacer(Modifier.width(8.dp))

        // Drag handle — the only part of the row with a drag gesture, so
        // vertical scroll of the whole panel still works everywhere else.
        // `pointerInput(item.id)` keeps the gesture alive across reorders
        // (the item.id doesn't change when the row moves).
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = stringResource(R.string.nowplaying_drag_handle_cd),
            tint = if (isDragging) FuturaColors.Emerald else FuturaColors.TextTertiary,
            modifier = Modifier
                .size(28.dp)
                .pointerInput(item.id) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragOffsetPx = 0f
                        },
                        onDragEnd = {
                            isDragging = false
                            // Clamp the target index to the list bounds.
                            val requestedDelta =
                                (dragOffsetPx / rowHeightPx).roundToInt()
                            val targetIndex = (currentIndex + requestedDelta)
                                .coerceIn(0, currentItemCount - 1)
                            if (targetIndex != currentIndex) {
                                onMove(currentIndex, targetIndex)
                            }
                            dragOffsetPx = 0f
                        },
                        onDragCancel = {
                            isDragging = false
                            dragOffsetPx = 0f
                        }
                    ) { change, amount ->
                        change.consume()
                        dragOffsetPx += amount
                        // Clamp so the row can't visually drift past the
                        // boundaries of the queue.
                        val maxUp = -currentIndex * rowHeightPx
                        val maxDown =
                            (currentItemCount - 1 - currentIndex) * rowHeightPx
                        dragOffsetPx = dragOffsetPx.coerceIn(maxUp, maxDown)
                    }
                }
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Similar suggestions
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun SimilarSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.library_smart_most_played),
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

@Composable
private fun SimilarRow(
    item: UpNextItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SimilarRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = FuturaColors.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.artist,
                color = FuturaColors.Cyan.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        // Add glyph in a small circular chip — visually distinct from
        // the drag handle on queue rows so the two actions don't get
        // confused.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FuturaColors.Cyan.copy(alpha = 0.15f))
                .border(
                    width = 1.dp,
                    color = FuturaColors.Cyan.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null, // row click carries the action
                tint = FuturaColors.Cyan,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Empty state
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyQueueState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.nowplaying_queue_empty),
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
// Compact "M:SS" for durations under an hour, "H:MM:SS" for longer
// tracks (rare but possible for DJ mixes). Zero duration renders as
// "--:--" to signal "unknown", which is what missing duration metadata
// looks like after a partial metadata extraction.

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
//   • Populated queue + suggestions — the happy path.
//   • Queue only, no suggestions    — the "Similar" section hidden.
//   • Empty queue                   — the empty-state message.

@Preview(
    name = "UpNextPanel — populated",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 480
)
@Composable
private fun UpNextPanelPreviewPopulated() {
    FuturaTheme {
        UpNextPanel(
            items = listOf(
                UpNextItem("1", "Midnight Protocol", "SYNTH DIVISION", 224L),
                UpNextItem("2", "Neon Rain", "VOID KISS", 198L),
                UpNextItem("3", "Afterglow", "SOLAR FIELD", 261L),
                UpNextItem("4", "Carbon Memory", "GHOSTLINE", 307L),
                UpNextItem("5", "Signal Decay", "SYNTH DIVISION", 215L)
            ),
            onReorder = { _, _ -> },
            onItemClick = {},
            onSimilarClick = {},
            similarSuggestions = listOf(
                UpNextItem("s1", "Cold Circuit", "VOID KISS", 189L),
                UpNextItem("s2", "Hollow Vector", "GHOSTLINE", 243L)
            )
        )
    }
}

@Preview(
    name = "UpNextPanel — no suggestions",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 320
)
@Composable
private fun UpNextPanelPreviewNoSuggestions() {
    FuturaTheme {
        UpNextPanel(
            items = listOf(
                UpNextItem("1", "Midnight Protocol", "SYNTH DIVISION", 224L),
                UpNextItem("2", "Neon Rain", "VOID KISS", 198L)
            ),
            onReorder = { _, _ -> },
            onItemClick = {},
            onSimilarClick = {}
        )
    }
}

@Preview(
    name = "UpNextPanel — empty",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 240
)
@Composable
private fun UpNextPanelPreviewEmpty() {
    FuturaTheme {
        UpNextPanel(
            items = emptyList(),
            onReorder = { _, _ -> },
            onItemClick = {},
            onSimilarClick = {}
        )
    }
}