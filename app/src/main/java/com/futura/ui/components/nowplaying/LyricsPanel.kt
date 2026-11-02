// futura-audio/app/src/main/java/com/futura/ui/components/nowplaying/LyricsPanel.kt
//
// Phase 2 — Now Playing "Synced Lyrics" panel.
//
// ── Responsibility ────────────────────────────────────────────────────────
// The second of the three swappable tab bodies inside MultiPanelDeck. Per
// the Phase 2 design spec:
//
//     "Panel 2 (Lyrics): Dual-column synced lyric view supporting duet
//      voices, line-level translations, and word-by-word highlight
//      animations."
//
// Delivers:
//
//   • A vertically-scrolling list of timed lyric lines.
//   • Auto-scroll to keep the current line roughly centred while
//     playback advances.
//   • Per-line voice styling — MAIN (emerald, centred), DUET_A (cyan,
//     left-aligned), DUET_B (amber, right-aligned) — giving the three
//     voices distinct colours *and* alignments.
//   • Line-level translations rendered below each line in italic
//     tertiary text with the same alignment as their parent line.
//   • Word-by-word highlight on the currently-sung line: words already
//     sung render in the voice accent, the word being sung right now
//     renders in white, and words yet to come render at 35% alpha.
//   • Tap-to-seek: tapping any line invokes `onSeekToLine(startMs)`.
//
// ── Component boundary ────────────────────────────────────────────────────
// `LyricLine`, `LyricWord`, and `LyricVoice` are UI-only value objects
// declared in this file. They are **not** the eventual `LyricLineK`
// produced by `OfflineLyricSynchronizer` / `LyricParser` — those will
// live in `engine/ml/` and `data/nowplaying/`. The `NowPlayingViewModel`
// will map `LyricLineK` → `LyricLine` at the callsite, keeping UI
// decoupled from parsing/persistence.
//
// All state that outlives a single frame is owned by the caller:
//
//   • `lines`             → owned by caller (NowPlayingViewModel)
//   • `currentPositionMs` → owned by caller (playback engine / audio tap)
//   • seek decisions      → caller via `onSeekToLine`
//
// Internal state is limited to:
//
//   • `LazyListState`     — for scroll + scroll-to-current-line
//   • the derived `currentLineIndex` and per-line `currentWordIndex`
//     used for word-by-word highlighting
//
// ── Efficiency notes ─────────────────────────────────────────────────────
// Playback position typically ticks at 4–60 Hz. To keep that from
// recomposing the whole list every tick, the panel splits row rendering
// into two paths:
//
//   • `OtherLyricLineRow` — non-current lines. Receives no
//     `currentPositionMs` parameter, so its recomposition scope does
//     not depend on playback advancing. Only recomposes when `isCurrent`
//     flips (i.e., twice per line transition).
//   • `CurrentLyricLineRow` — the one active line. Re-reads
//     `currentPositionMs` each frame, but its expensive work (building
//     the word-highlighted `AnnotatedString`) is memoised on
//     `currentWordIndex`, which changes only when crossing word
//     boundaries. So per-tick cost is: one small `indexOfLast` scan on
//     ~5–10 words.
//
// Scroll-to-current-line is driven by `LaunchedEffect(currentLineIndex)`,
// so it fires once per line transition, not per frame. LazyListState
// cancels any in-flight scroll when a new one starts, so rapid seeks
// don't queue animations.
//
// ── Precondition ─────────────────────────────────────────────────────────
// `lines` MUST be sorted by `startMs` ascending. This is guaranteed for
// any LRC file (the format requires it) and is enforced by the future
// `LyricParser`. The `findCurrentLineIndex` helper exploits the sorted
// order to early-exit on the first future line.
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
//   nowplaying_lyrics_empty
//
// No future project class or file is referenced.

package com.futura.ui.components.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.R
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme

// ═════════════════════════════════════════════════════════════════════════
// Public data model
// ═════════════════════════════════════════════════════════════════════════

/** Which voice sings a given lyric line. */
enum class LyricVoice {
    /** Single lead vocal. Emerald accent, centred. */
    MAIN,

    /** Duet part A. Cyan accent, left-aligned. */
    DUET_A,

    /** Duet part B. Amber accent, right-aligned. */
    DUET_B
}

/**
 * A single word inside a lyric line, with its own timing. Used only for
 * word-by-word highlighting on the currently-sung line; line-level
 * metadata (text, translation, voice, span) is carried by [LyricLine].
 *
 * @param text Word text (no surrounding whitespace).
 * @param startMs Word's start timestamp in milliseconds from track start.
 * @param endMs Word's end timestamp in milliseconds from track start.
 */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * A single timed lyric line.
 *
 * @param text Line text as displayed (pre-word-tokenised, so words are
 *        the same characters as the substrings of [text]).
 * @param startMs Line start timestamp in ms from track start.
 * @param endMs Line end timestamp in ms from track start.
 * @param translation Optional line-level translation shown below the
 *        line in italic tertiary text. `null` or blank → no translation
 *        row is rendered.
 * @param voice Which singer sings this line.
 * @param words Optional word-level timing. When non-null and non-empty
 *        AND the line is currently active, word-by-word highlighting is
 *        used for that line. When null, the whole line renders in the
 *        accent colour with no per-word granularity.
 */
data class LyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val translation: String? = null,
    val voice: LyricVoice = LyricVoice.MAIN,
    val words: List<LyricWord>? = null
)

// ═════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════

/**
 * Synced lyrics tab body.
 *
 * @param lines              Timed lyric lines, sorted by [LyricLine.startMs]
 *                           ascending.
 * @param currentPositionMs  Current playback position in milliseconds.
 *                           Typically supplied by a `State<Long>` in the
 *                           parent so this composable re-reads it each
 *                           frame.
 * @param modifier           Optional outer modifier. Panel fills the
 *                           space it is given and manages its own
 *                           scrolling.
 * @param onSeekToLine       Optional callback fired when a lyric line is
 *                           tapped, with that line's `startMs`. Enables
 *                           tap-to-seek-jump. When null, lines are inert.
 */
@Composable
fun LyricsPanel(
    lines: List<LyricLine>,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    onSeekToLine: ((Long) -> Unit)? = null
) {
    if (lines.isEmpty()) {
        EmptyState(modifier = modifier)
        return
    }

    // Only recomputes when lines or position changes — the work is a
    // single forward scan with early exit, O(lines) worst case, but in
    // practice O(played lines) because the scan breaks at the first
    // future line.
    val currentLineIndex = remember(lines, currentPositionMs) {
        findCurrentLineIndex(lines, currentPositionMs)
    }

    val listState = rememberLazyListState()

    // Auto-scroll when the active line changes. Putting the current line
    // ~2 rows from the top keeps a bit of "context" visible above it,
    // which reads better than strict centring for continuous reading.
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex in lines.indices) {
            val target = (currentLineIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(items = lines) { index, line ->
            val isCurrent = index == currentLineIndex
            val tapHandler: (() -> Unit)? = onSeekToLine?.let { cb ->
                { cb(line.startMs) }
            }
            if (isCurrent) {
                CurrentLyricLineRow(
                    line = line,
                    currentPositionMs = currentPositionMs,
                    onClick = tapHandler
                )
            } else {
                OtherLyricLineRow(
                    line = line,
                    onClick = tapHandler
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Non-current line row
// ═════════════════════════════════════════════════════════════════════════
//
// Deliberately does NOT read `currentPositionMs`. Its inputs are just
// `line` and `onClick`, so it will not recompose when playback position
// ticks — only when `isCurrent` flips (which the parent handles by
// swapping between this and CurrentLyricLineRow).

@Composable
private fun OtherLyricLineRow(
    line: LyricLine,
    onClick: (() -> Unit)?
) {
    val style = voiceStyle(line.voice)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalAlignment = style.horizontalAlignment
    ) {
        Text(
            text = line.text,
            color = style.accent.copy(alpha = 0.55f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            textAlign = style.textAlign,
            modifier = Modifier.fillMaxWidth()
        )
        if (!line.translation.isNullOrBlank()) {
            Text(
                text = line.translation,
                color = FuturaColors.TextTertiary.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif,
                textAlign = style.textAlign,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Current line row
// ═════════════════════════════════════════════════════════════════════════
//
// The only row that reads `currentPositionMs`. Word highlighting is
// memoised on the derived `currentWordIndex` so the AnnotatedString is
// only rebuilt when crossing a word boundary — not every frame.

@Composable
private fun CurrentLyricLineRow(
    line: LyricLine,
    currentPositionMs: Long,
    onClick: (() -> Unit)?
) {
    val style = voiceStyle(line.voice)
    val words = line.words

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = style.horizontalAlignment
    ) {
        if (words.isNullOrEmpty()) {
            // No word timings — render the whole line in the accent
            // colour at full opacity. Bold so it stands out from
            // surrounding non-current rows.
            Text(
                text = line.text,
                color = style.accent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                textAlign = style.textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Word-by-word highlight. currentWordIndex changes at word
            // boundaries, so the memoised AnnotatedString rebuilds at
            // most once per word — not once per frame.
            val currentWordIndex = remember(words, currentPositionMs) {
                words.indexOfLast { currentPositionMs >= it.startMs }
            }
            val annotated = remember(words, currentWordIndex, style.accent) {
                buildAnnotatedString {
                    words.forEachIndexed { i, word ->
                        val color = when {
                            i == currentWordIndex -> FuturaColors.White
                            i < currentWordIndex  -> style.accent
                            else                  -> style.accent.copy(alpha = 0.35f)
                        }
                        withStyle(SpanStyle(color = color)) {
                            append(word.text)
                        }
                        if (i < words.size - 1) append(' ')
                    }
                }
            }
            Text(
                text = annotated,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                textAlign = style.textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!line.translation.isNullOrBlank()) {
            Text(
                text = line.translation,
                color = FuturaColors.TextTertiary,
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif,
                textAlign = style.textAlign,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Voice styling
// ═════════════════════════════════════════════════════════════════════════

/**
 * Per-voice colour + alignment triple, computed once per row.
 *
 * Mapping chosen to be consistent with the app's palette discipline:
 *   • MAIN   → Emerald (primary), centred — the default single voice.
 *   • DUET_A → Cyan (secondary), start-aligned — reads as "left singer".
 *   • DUET_B → Amber (tertiary/warning), end-aligned — reads as "right
 *              singer", reusing the accent that already signals "alt
 *              state" elsewhere in the app (alarm snooze, warning pill).
 */
private data class VoiceStyle(
    val accent: Color,
    val textAlign: TextAlign,
    val horizontalAlignment: Alignment.Horizontal
)

private fun voiceStyle(voice: LyricVoice): VoiceStyle = when (voice) {
    LyricVoice.MAIN -> VoiceStyle(
        accent = FuturaColors.Emerald,
        textAlign = TextAlign.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    )
    LyricVoice.DUET_A -> VoiceStyle(
        accent = FuturaColors.Cyan,
        textAlign = TextAlign.Start,
        horizontalAlignment = Alignment.Start
    )
    LyricVoice.DUET_B -> VoiceStyle(
        accent = FuturaColors.Amber,
        textAlign = TextAlign.End,
        horizontalAlignment = Alignment.End
    )
}

// ═════════════════════════════════════════════════════════════════════════
// Current-line resolution
// ═════════════════════════════════════════════════════════════════════════

/**
 * Returns the index of the line whose interval contains [currentMs], or
 * the last line whose startMs is in the past if [currentMs] falls in a
 * gap between lines. Returns -1 if [currentMs] is before the first
 * line's startMs (i.e., lyrics haven't started yet).
 *
 * Exploits the sorted-by-startMs precondition: scanning stops as soon as
 * a future line is encountered, so per-tick cost is proportional to the
 * number of lines already sung — effectively O(1) for typical playback.
 */
private fun findCurrentLineIndex(
    lines: List<LyricLine>,
    currentMs: Long
): Int {
    var lastStarted = -1
    for (i in lines.indices) {
        if (currentMs >= lines[i].startMs) {
            lastStarted = i
        } else {
            break
        }
    }
    return lastStarted
}

// ═════════════════════════════════════════════════════════════════════════
// Empty state
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.nowplaying_lyrics_empty),
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
// Four previews:
//   • Duet with words + translation — exercises word highlighting,
//     voice colours, alignment, and translation rendering simultaneously.
//   • Plain single-voice — no word timings, whole line in accent colour.
//   • Inter-line gap — current position is between two lines; the
//     last-started line stays highlighted as "just finished".
//   • Empty state — the string that appears when no lyrics exist.

private val PREVIEW_DUET_LYRICS: List<LyricLine> = listOf(
    LyricLine(
        text = "In the silence before the dawn",
        startMs = 4_000L,
        endMs = 7_000L,
        voice = LyricVoice.MAIN
    ),
    LyricLine(
        text = "I hear the wires hum",
        startMs = 7_000L,
        endMs = 10_000L,
        voice = LyricVoice.DUET_A
    ),
    LyricLine(
        text = "A signal rising in my chest",
        startMs = 10_000L,
        endMs = 13_000L,
        voice = LyricVoice.DUET_B
    ),
    LyricLine(
        text = "We are the neon and the light",
        startMs = 13_000L,
        endMs = 18_000L,
        translation = "私たちはネオンと光",
        voice = LyricVoice.MAIN,
        words = listOf(
            LyricWord("We",     13_000L, 13_500L),
            LyricWord("are",    13_500L, 14_000L),
            LyricWord("the",    14_000L, 14_300L),
            LyricWord("neon",   14_300L, 15_200L),
            LyricWord("and",    15_200L, 16_000L),
            LyricWord("the",    16_000L, 16_300L),
            LyricWord("light",  16_300L, 18_000L)
        )
    ),
    LyricLine(
        text = "Together we ignite",
        startMs = 18_000L,
        endMs = 21_000L,
        voice = LyricVoice.MAIN
    )
)

@Preview(
    name = "LyricsPanel — duet with words",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 400
)
@Composable
private fun LyricsPanelPreviewDuet() {
    FuturaTheme {
        LyricsPanel(
            lines = PREVIEW_DUET_LYRICS,
            // 15_500ms — mid-"neon", so word highlighting shows
            // "We are the" (sung, emerald), "neon" (in progress, white),
            // "and the light" (upcoming, dim emerald).
            currentPositionMs = 15_500L,
            onSeekToLine = {}
        )
    }
}

@Preview(
    name = "LyricsPanel — plain single voice",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 300
)
@Composable
private fun LyricsPanelPreviewPlain() {
    val plainLines = listOf(
        LyricLine("First light on the empty street", 0L, 3_000L),
        LyricLine("A quiet hum beneath my feet", 3_000L, 6_000L),
        LyricLine("Every window is a mirror", 6_000L, 9_000L),
        LyricLine("Showing me the way back clearer", 9_000L, 12_000L)
    )
    FuturaTheme {
        LyricsPanel(
            lines = plainLines,
            currentPositionMs = 3_500L
        )
    }
}

@Preview(
    name = "LyricsPanel — inter-line gap",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 300
)
@Composable
private fun LyricsPanelPreviewGap() {
    // 21_500ms — after PREVIEW_DUET_LYRICS's last line ends at 21_000ms.
    // The last line stays highlighted as "just finished".
    FuturaTheme {
        LyricsPanel(
            lines = PREVIEW_DUET_LYRICS,
            currentPositionMs = 21_500L
        )
    }
}

@Preview(
    name = "LyricsPanel — empty",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    heightDp = 200
)
@Composable
private fun LyricsPanelPreviewEmpty() {
    FuturaTheme {
        LyricsPanel(
            lines = emptyList(),
            currentPositionMs = 0L
        )
    }
}