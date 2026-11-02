// futura-audio/app/src/main/java/com/futura/data/nowplaying/OfflineLyricSynchronizer.kt
//
// Phase 5 — offline lyric alignment.

package com.futura.data.nowplaying

import com.futura.ui.components.nowplaying.LyricLine
import com.futura.ui.components.nowplaying.LyricVoice
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Aligns plain-text lyrics using an optional asynchronous aligner or a
 * deterministic proportional-duration fallback.
 */
object OfflineLyricSynchronizer {

    suspend fun synchronize(
        plainLyrics: String,
        durationMs: Long,
        leadInMs: Long = 0L,
        lineAligner: LineAligner? = null
    ): List<LyricLine> {
        if (durationMs <= 0L || leadInMs !in 0 until durationMs) return emptyList()

        val lines = extractLines(plainLyrics)
        if (lines.isEmpty()) return emptyList()

        if (lineAligner != null) {
            val startTimes = try {
                lineAligner.align(lines, durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (isValidAlignment(startTimes, lines.size, durationMs, leadInMs)) {
                return buildFromStartTimes(lines, startTimes!!, durationMs)
            }
        }

        return buildFromHeuristic(lines, durationMs, leadInMs)
    }

    suspend fun synchronizeFile(
        file: File,
        durationMs: Long,
        leadInMs: Long = 0L,
        lineAligner: LineAligner? = null
    ): List<LyricLine> {
        return try {
            synchronize(file.readText(Charsets.UTF_8), durationMs, leadInMs, lineAligner)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun interface LineAligner {
        suspend fun align(lines: List<String>, durationMs: Long): List<Long>
    }

    private fun isValidAlignment(
        startTimes: List<Long>?,
        expectedCount: Int,
        durationMs: Long,
        leadInMs: Long
    ): Boolean {
        if (startTimes == null || startTimes.size != expectedCount) return false
        if (startTimes.any { it < leadInMs || it > durationMs }) return false
        return startTimes.zipWithNext().all { (current, next) -> current <= next }
    }

    private fun buildFromStartTimes(
        lines: List<String>,
        startTimes: List<Long>,
        durationMs: Long
    ): List<LyricLine> =
        lines.mapIndexed { index, text ->
            LyricLine(
                text = text,
                startMs = startTimes[index],
                endMs = startTimes.getOrNull(index + 1) ?: durationMs,
                translation = null,
                voice = LyricVoice.MAIN,
                words = null
            )
        }

    private fun buildFromHeuristic(
        lines: List<String>,
        durationMs: Long,
        leadInMs: Long
    ): List<LyricLine> {
        val weights = lines.map { it.length.coerceAtLeast(MIN_WEIGHT_CHARS) }
        val totalWeight = weights.sum().toDouble()
        val availableMs = (durationMs - leadInMs).toDouble()
        var cursorMs = leadInMs.toDouble()

        return lines.mapIndexed { index, text ->
            val lineDurationMs = availableMs * (weights[index] / totalWeight)
            val startMs = cursorMs.toLong()
            val endMs = (cursorMs + lineDurationMs).toLong()
            cursorMs += lineDurationMs
            LyricLine(
                text = text,
                startMs = startMs,
                endMs = endMs,
                translation = null,
                voice = LyricVoice.MAIN,
                words = null
            )
        }
    }

    private fun extractLines(raw: String): List<String> =
        raw.removeBom()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { stripLeadingTimestamps(it.trim()) }
            .filter { it.isNotEmpty() }
            .filterNot { METADATA_LINE_REGEX.matches(it) }

    private fun stripLeadingTimestamps(line: String): String {
        var result = line
        while (true) {
            val match = LEADING_TIMESTAMP_REGEX.matchAt(result, 0) ?: break
            result = result.substring(match.range.last + 1).trimStart()
        }
        return result
    }

    private fun String.removeBom(): String =
        if (isNotEmpty() && this[0] == '\uFEFF') substring(1) else this

    private val LEADING_TIMESTAMP_REGEX =
        Regex("""^\[\d{1,3}:\d{2}(?:\.\d{2,3})?]""")

    private val METADATA_LINE_REGEX = Regex(
        """^\[(ar|ti|al|by|au|length|re|ve|tool|offset):.*]$""",
        RegexOption.IGNORE_CASE
    )

    private const val MIN_WEIGHT_CHARS = 4
}
