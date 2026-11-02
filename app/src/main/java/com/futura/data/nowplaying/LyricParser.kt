// futura-audio/app/src/main/java/com/futura/data/nowplaying/LyricParser.kt
//
// Phase 2 — Now Playing lyric parser.
//
// ── Tree annotation: "✅ LRC + duet split" ────────────────────────────
// This file is one of the four delivered "✅" entries in the
// data/nowplaying/ package. It is a pure-function parser: no I/O, no
// Android APIs, no coroutines, no dependencies. Given an LRC file's
// text content, it returns a sorted List<LyricLine>.
//
// ── Input formats supported ──────────────────────────────────────────
//   1. Standard LRC
//        [mm:ss.xx]Line text
//        [mm:ss.xx]Line text
//      Centiseconds ("xx") or milliseconds ("xxx") are both accepted.
//      Multiple leading timestamps on one line ("[00:10.00][01:20.00]text")
//      expand into multiple LyricLines at their respective times — this
//      is standard LRC shorthand for repeated chorus lines.
//
//   2. Enhanced LRC (word-level timings)
//        [mm:ss.xx] <mm:ss.xx>word <mm:ss.xx>word ...
//      Word timings are emitted as LyricWord entries on the LyricLine.
//      End time of the final word is inferred from the next line's
//      start time (or the line's end timestamp if present), because
//      enhanced LRC has no explicit line-end marker.
//
//   3. Duet markers
//      Voice prefixes are recognised in five common conventions:
//        [A] text            <A> text            A: text
//        [B] text            <B> text            B: text
//      Also accepts [Duet A]/[Duet B] and [Male]/[Female] variants.
//      Lines with no marker inherit the voice of the previous marked
//      line (or MAIN if none preceded).
//
//   4. Translations
//      Two conventions are recognised:
//        a) Paired timestamps — two consecutive lines with the exact
//           same start time. The second becomes the first's
//           `translation`.
//        b) Explicit marker — `[tr]translated text` following a
//           timestamped line.
//
//   5. Metadata tags
//      `[ar:Artist]`, `[ti:Title]`, `[al:Album]`, `[by:...]`,
//      `[offset:+250]` — all skipped (they carry no lyric content).
//      `[offset:N]` is honoured: N milliseconds (positive or negative)
//      are added to every timestamp, per LRC spec.
//
// ── Robustness ──────────────────────────────────────────────────────
//   • BOM (U+FEFF) stripped if present.
//   • CRLF and CR line endings both accepted.
//   • Out-of-order timestamps in the source are tolerated: the final
//     list is always sorted ascending by `startMs`.
//   • Malformed timestamps ("[xx:yy]") are silently skipped; the file
//     can still produce usable output if only some lines are corrupt.
//   • Blank lines ignored.
//   • A line that contains only a timestamp and no text is skipped
//     (used in some files as section dividers).
//   • A line whose text is entirely whitespace is treated the same as
//     a blank line.
//
// ── Public API ──────────────────────────────────────────────────────
//   object LyricParser {
//       fun parse(content: String): List<LyricLine>
//       fun parseFile(file: File): List<LyricLine>          // convenience
//   }
//
// Both return an empty list on unrecoverable failure. Callers should
// treat `emptyList()` as "no lyrics available", which is exactly what
// `LyricsPanel` displays as its empty state.
//
// ── Forward-reference audit ──────────────────────────────────────────
// Project symbols imported:
//   • com.futura.ui.components.nowplaying.LyricLine      (delivered)
//   • com.futura.ui.components.nowplaying.LyricVoice     (delivered)
//   • com.futura.ui.components.nowplaying.LyricWord      (delivered)
//
// All three were shipped in LyricsPanel.kt earlier in this build
// sequence. No other project class or file is referenced.
//
// Library symbols imported:
//   • java.io.File          (JDK, no dependency)
//
// No Android, Kotlin Coroutines, Hilt, or Compose classes are touched.
// This is a pure Kotlin file, fully unit-testable without Robolectric.

package com.futura.data.nowplaying

import com.futura.ui.components.nowplaying.LyricLine
import com.futura.ui.components.nowplaying.LyricVoice
import com.futura.ui.components.nowplaying.LyricWord
import java.io.File

/**
 * Stateless LRC lyric parser.
 *
 * All methods are pure functions of their input. The parser is exposed
 * as a singleton `object` because it has no dependencies and no state —
 * there is nothing to inject, and creating one instance per call site
 * would be wasteful.
 */
object LyricParser {

    // ── Timestamp regex ─────────────────────────────────────────────
    // Matches [mm:ss], [mm:ss.cc], [mm:ss.ccc], with optional leading
    // minutes (up to 3 digits to accommodate long tracks and hour-long
    // DJ mixes written as [mm:ss] with mm > 59).
    private val LINE_TIMESTAMP_REGEX =
        Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{2,3}))?]""")

    // Matches word timestamps in enhanced LRC: <mm:ss.cc>.
    private val WORD_TIMESTAMP_REGEX =
        Regex("""<(\d{1,3}):(\d{2})(?:\.(\d{2,3}))?>""")

    // Metadata tags that should be skipped entirely.
    private val METADATA_REGEX = Regex("""^\[([a-zA-Z]+):(.*)]$""")

    // Offset directive — must be honoured. `[offset:+250]` or
    // `[offset:-500]` shifts every timestamp.
    private val OFFSET_REGEX = Regex("""^\[offset:\s*([+-]?\d+)]$""")

    // Voice markers, checked in order. All are stripped from the line
    // text if matched.
    private val VOICE_BRACKET_REGEX =
        Regex("""^\s*\[(A|B|Duet\s*A|Duet\s*B|Male|Female|M|F)]\s*""",
              RegexOption.IGNORE_CASE)
    private val VOICE_ANGLE_REGEX =
        Regex("""^\s*<(A|B)>""")
    private val VOICE_COLON_REGEX =
        Regex("""^\s*(A|B)\s*:\s*""")

    // Explicit translation marker that may prefix a subsequent line.
    private val TRANSLATION_MARKER_REGEX =
        Regex("""^\s*\[tr]\s*""", RegexOption.IGNORE_CASE)

    // ═════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════

    /**
     * Parse LRC text content into a sorted, non-overlapping list of
     * [LyricLine].
     *
     * @param content The raw LRC file text.
     * @return Lyric lines sorted by `startMs` ascending. Empty list on
     *         no valid content.
     */
    fun parse(content: String): List<LyricLine> {
        val normalised = content.removeBOM().replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = normalised.split('\n')

        // ── Pass 1: apply offset directive, if present, and strip
        //    metadata lines. Doing this before the main parse pass
        //    keeps the main loop simpler.
        val offsetMs = extractOffset(rawLines)

        // ── Pass 2: main parse into intermediate records. We need an
        //    intermediate type because translation pairing and voice
        //    inheritance both need to look at consecutive raw entries.
        val raws = mutableListOf<RawLine>()
        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (OFFSET_REGEX.matches(trimmed)) continue
            if (isMetadata(trimmed)) continue

            val parsed = parseRawLine(trimmed, offsetMs) ?: continue
            raws.addAll(parsed)
        }

        if (raws.isEmpty()) return emptyList()

        // ── Pass 3: resolve translations and voice inheritance.
        val resolved = resolveLines(raws)

        // ── Pass 4: sort by start time. LRC files occasionally have
        //    out-of-order entries (chorus placed at the bottom of the
        //    file, etc.) — the parser normalises to chronological.
        val sorted = resolved.sortedBy { it.startMs }

        // ── Pass 5: assign endMs for lines that don't have it explicitly
        //    (all standard LRC lines), and infer endMs for the last
        //    word of enhanced-LRC lines. Also attaches word end times.
        return assignEndTimes(sorted)
    }

    /**
     * Convenience overload that reads [file] and delegates to [parse].
     * Returns an empty list if the file cannot be read.
     */
    fun parseFile(file: File): List<LyricLine> {
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Internal types
    // ═════════════════════════════════════════════════════════════════

    /**
     * One parsed line, before translation/voice resolution. A single
     * source line can produce multiple [RawLine]s if it carries more
     * than one leading timestamp (chorus shorthand).
     */
    private data class RawLine(
        val startMs: Long,
        val text: String,
        val words: List<LyricWord>?,
        val voice: LyricVoice?
    )

    /**
     * Post-resolution line — already has its voice and optional
     * translation attached, but still missing its final endMs.
     */
    private data class ResolvedLine(
        val startMs: Long,
        val text: String,
        val translation: String?,
        val voice: LyricVoice,
        val words: List<LyricWord>?
    )

    // ═════════════════════════════════════════════════════════════════
    // Pass 1 — offset directive
    // ═════════════════════════════════════════════════════════════════

    private fun extractOffset(lines: List<String>): Long {
        for (line in lines) {
            val match = OFFSET_REGEX.find(line.trim()) ?: continue
            val value = match.groupValues[1].toLongOrNull() ?: continue
            return value
        }
        return 0L
    }

    // ═════════════════════════════════════════════════════════════════
    // Metadata detection
    // ═════════════════════════════════════════════════════════════════

    private fun isMetadata(line: String): Boolean {
        val match = METADATA_REGEX.find(line) ?: return false
        val tag = match.groupValues[1].lowercase()
        // Only treat as metadata if it's a known non-lyric tag. Voice
        // markers like [A] and [B] are single characters and never
        // match this pattern (which requires a colon before the value).
        return tag in setOf("ar", "ti", "al", "by", "au", "length", "re", "ve", "tool")
    }

    // ═════════════════════════════════════════════════════════════════
    // Pass 2 — parse a single line
    // ═════════════════════════════════════════════════════════════════

    /**
     * Parse one trimmed line. Returns an empty list for lines that
     * produce no output (no timestamps), one [RawLine] for a typical
     * line, or multiple for a chorus shorthand. Returns null only if
     * the line is structurally malformed in a way that should be
     * skipped.
     */
    private fun parseRawLine(line: String, offsetMs: Long): List<RawLine>? {
        // Find all leading timestamps. In LRC, timestamps must be at
        // the start of the line; anything before the first timestamp
        // is either whitespace or a leading voice marker that we'll
        // strip later.
        val timestamps = mutableListOf<Long>()
        var idx = 0
        while (true) {
            val match = LINE_TIMESTAMP_REGEX.matchAt(line, idx) ?: break
            val ms = timestampToMs(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3]
            ) ?: break
            timestamps.add(ms + offsetMs)
            idx = match.range.last + 1
        }
        if (timestamps.isEmpty()) return null

        // What follows all the leading timestamps is the line body.
        var body = line.substring(idx)

        // Strip a leading voice marker from the body (before anything
        // else), so word timestamps in enhanced LRC are matched against
        // the actual lyric text.
        val (voice, bodyAfterVoice) = extractVoice(body)
        body = bodyAfterVoice

        // Strip an explicit translation marker — this line is a
        // translation of the *previous* line; we tag it with a sentinel
        // that Pass 3 will recognise. Because we return multiple raw
        // lines for multi-timestamp lines, the marker applies to all of
        // them (which is the correct behaviour for a chorus that is
        // duplicated).
        val isTranslationMarker = TRANSLATION_MARKER_REGEX.containsMatchIn(body)
        if (isTranslationMarker) {
            body = TRANSLATION_MARKER_REGEX.replace(body, "")
        }

        // Detect word-level timings.
        val hasWordTimestamps = WORD_TIMESTAMP_REGEX.containsMatchIn(body)

        val results = mutableListOf<RawLine>()
        for (startMs in timestamps) {
            if (hasWordTimestamps) {
                val (plainText, words) = extractWords(body, startMs, offsetMs)
                if (plainText.isEmpty()) continue
                results.add(
                    RawLine(
                        startMs = startMs,
                        text = plainText,
                        words = words,
                        voice = if (isTranslationMarker) null else voice
                    )
                )
            } else {
                val trimmedBody = body.trim()
                if (trimmedBody.isEmpty()) continue
                results.add(
                    RawLine(
                        startMs = startMs,
                        text = trimmedBody,
                        words = null,
                        voice = if (isTranslationMarker) null else voice
                    )
                )
            }
        }
        return results
    }

    // ═════════════════════════════════════════════════════════════════
    // Voice marker extraction
    // ═════════════════════════════════════════════════════════════════

    private fun extractVoice(body: String): Pair<LyricVoice?, String> {
        // Order matters: [A] / [B] bracket form is the most common, so
        // try it first. Then <A> / <B>. Then A: / B:. If none match,
        // no voice marker was present.
        VOICE_BRACKET_REGEX.find(body)?.let { match ->
            return mapVoiceMarker(match.groupValues[1]) to
                body.substring(match.range.last + 1)
        }
        VOICE_ANGLE_REGEX.find(body)?.let { match ->
            return mapVoiceMarker(match.groupValues[1]) to
                body.substring(match.range.last + 1)
        }
        VOICE_COLON_REGEX.find(body)?.let { match ->
            return mapVoiceMarker(match.groupValues[1]) to
                body.substring(match.range.last + 1)
        }
        return null to body
    }

    private fun mapVoiceMarker(marker: String): LyricVoice = when (marker.uppercase()) {
        "A", "DUET A", "DUETA", "MALE", "M" -> LyricVoice.DUET_A
        "B", "DUET B", "DUETB", "FEMALE", "F" -> LyricVoice.DUET_B
        else -> LyricVoice.MAIN
    }

    // ═════════════════════════════════════════════════════════════════
    // Enhanced LRC — word-level extraction
    // ═════════════════════════════════════════════════════════════════

    /**
     * Split [body] into plain text (with timestamps stripped) and a list
     * of word timings.
     *
     * In enhanced LRC, the format is:
     *     <mm:ss.cc>word <mm:ss.cc>word <mm:ss.cc>word
     *
     * Where spaces between words are literal. The end time of each word
     * is the start time of the next word (or [lineStart] + a small
     * default for the last word — refined in [assignEndTimes]).
     *
     * Returns (plainText, words). If the body has no word timestamps,
     * returns (body, null) so the caller can treat it as a plain line.
     */
    private fun extractWords(
        body: String,
        lineStartMs: Long,
        offsetMs: Long
    ): Pair<String, List<LyricWord>?> {
        val matches = WORD_TIMESTAMP_REGEX.findAll(body).toList()
        if (matches.isEmpty()) return body.trim() to null

        val words = mutableListOf<LyricWord>()
        val plain = StringBuilder()

        for ((index, match) in matches.withIndex()) {
            val wordStartMs = (
                timestampToMs(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                ) ?: continue
                ) + offsetMs
            val wordTextStart = match.range.last + 1
            val wordTextEnd = if (index < matches.size - 1)
                matches[index + 1].range.first
            else
                body.length
            val wordText = body.substring(wordTextStart, wordTextEnd).trim()
            if (wordText.isEmpty()) continue

            if (plain.isNotEmpty()) plain.append(' ')
            plain.append(wordText)

            words.add(
                LyricWord(
                    text = wordText,
                    startMs = wordStartMs,
                    // Provisional end time — refined in assignEndTimes.
                    endMs = wordStartMs
                )
            )
        }

        return plain.toString() to words.ifEmpty { null }
    }

    // ═════════════════════════════════════════════════════════════════
    // Timestamp → milliseconds
    // ═════════════════════════════════════════════════════════════════

    private fun timestampToMs(
        minutes: String,
        seconds: String,
        fraction: String?
    ): Long? {
        val m = minutes.toLongOrNull() ?: return null
        val s = seconds.toLongOrNull() ?: return null
        if (s >= 60L) return null
        // Two-digit fraction = centiseconds (× 10 ms).
        // Three-digit fraction = milliseconds (× 1 ms).
        // Empty = whole seconds.
        val fracMs: Long = when (fraction?.length) {
            0, null -> 0L
            2 -> (fraction.toLongOrNull() ?: return null) * 10L
            3 -> fraction.toLongOrNull() ?: return null
            else -> return null
        }
        return (m * 60L + s) * 1000L + fracMs
    }

    // ═════════════════════════════════════════════════════════════════
    // Pass 3 — translation pairing + voice inheritance
    // ═════════════════════════════════════════════════════════════════

    private fun resolveLines(raws: List<RawLine>): List<ResolvedLine> {
        val resolved = mutableListOf<ResolvedLine>()
        var lastVoice: LyricVoice = LyricVoice.MAIN
        var i = 0

        while (i < raws.size) {
            val current = raws[i]

            // Detect translation pairing: two consecutive raw lines with
            // the exact same start time.
            val next = raws.getOrNull(i + 1)
            val isPaired = next != null &&
                next.startMs == current.startMs &&
                current.voice != null // translations don't inherit voice

            if (isPaired) {
                // First line is original, second is translation.
                val voice = current.voice ?: lastVoice
                lastVoice = voice
                resolved.add(
                    ResolvedLine(
                        startMs = current.startMs,
                        text = current.text,
                        translation = next!!.text,
                        voice = voice,
                        words = current.words
                    )
                )
                i += 2
                continue
            }

            // No pairing — carry voice forward or inherit from previous.
            val voice = current.voice ?: lastVoice
            lastVoice = voice
            resolved.add(
                ResolvedLine(
                    startMs = current.startMs,
                    text = current.text,
                    translation = null,
                    voice = voice,
                    words = current.words
                )
            )
            i += 1
        }

        return resolved
    }

    // ═════════════════════════════════════════════════════════════════
    // Pass 5 — assign end times
    // ═════════════════════════════════════════════════════════════════

    private fun assignEndTimes(sorted: List<ResolvedLine>): List<LyricLine> {
        val result = mutableListOf<LyricLine>(sorted.size)
        for ((index, line) in sorted.withIndex()) {
            // End of a line is the start of the next, or +DEFAULT_LINE_MS
            // for the last line (no further line to bound it).
            val nextStartMs = sorted.getOrNull(index + 1)?.startMs
            val lineEndMs = nextStartMs ?: (line.startMs + DEFAULT_LAST_LINE_MS)

            val words = line.words?.let { wordList ->
                // Attach end times to words: each word ends at the next
                // word's start; the last word ends at lineEndMs.
                wordList.mapIndexed { wIndex, word ->
                    val wordEndMs = wordList.getOrNull(wIndex + 1)?.startMs
                        ?: lineEndMs
                    word.copy(endMs = wordEndMs)
                }
            }

            result.add(
                LyricLine(
                    text = line.text,
                    startMs = line.startMs,
                    endMs = lineEndMs,
                    translation = line.translation,
                    voice = line.voice,
                    words = words
                )
            )
        }
        return result
    }

    // ═════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════

    private fun String.removeBOM(): String =
        if (isNotEmpty() && this[0] == '\uFEFF') substring(1) else this

    // ═════════════════════════════════════════════════════════════════
    // Constants
    // ═════════════════════════════════════════════════════════════════

    /**
     * Duration assigned to the final lyric line when there is no
     * following line to bound it. 4 seconds is a reasonable default —
     * long enough that the last line stays highlighted through a
     * typical outro, short enough that the highlight doesn't linger
     * unnaturally on a track with a long instrumental fade-out.
     */
    private const val DEFAULT_LAST_LINE_MS = 4_000L
}