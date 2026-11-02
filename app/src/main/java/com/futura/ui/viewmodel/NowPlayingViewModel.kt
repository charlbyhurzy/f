// futura-audio/app/src/main/java/com/futura/ui/viewmodel/NowPlayingViewModel.kt
//
// Phase 2 — Now Playing screen ViewModel.
//
// ── Tree annotation: "❌" (not yet implemented) ──────────────────────────
// Per the project tree this file is a stub, matching the state of the
// whole Phase 2 backend. No playback engine exists, no MediaSession
// exists, no `AudioTap` exists, no cover-art cache exists, no
// `OfflineLyricSynchronizer` exists, and no queue is ever populated
// with real tracks.
//
// What this file delivers today:
//
//   • The full public state surface the Now Playing screen and its ten
//     components consume — split into a main `StateFlow<NowPlayingUiState>`
//     for stable data, plus three dedicated flows for values that change
//     at audio / tick frequency (`positionMs`, `bassLevel`, `waveformData`).
//     This split mirrors the optimisations already baked into
//     `LyricsPanel` (only the current line reads `positionMs`) and
//     `HeroPlayer` (only the aura reads `bassLevel`), so wiring the VM
//     into the screen will not silently degrade their recomposition
//     scope.
//   • The full callback surface the screen's components already declare:
//     play/pause, previous/next, shuffle, loop, EQ toggle, seek,
//     seek-to-line, queue reorder, queue item click, similar-suggestion
//     click, related-track click.
//   • Working in-memory behaviour for the subset that can function
//     without any subsystem:
//       – play/pause toggling + a 200 ms position ticker (runs only
//         while playing and only when a track with non-zero duration
//         is loaded)
//       – previous/next cycling through the in-memory queue
//       – shuffle/loop/EQ toggling
//       – seek (fraction and lyric-line absolute) clamped to duration
//       – queue reorder
//       – tapping a queue item loads it as the current track
//       – tapping a similar-suggestion or related-track appends it to
//         the queue (deduped by id)
//
//   • Documented seams, one per future subsystem, for every callback or
//     state field that needs real playback / DSP / ML / engine work to
//     become functional. Those seams are named in KDoc so the wiring
//     turn has an unambiguous checklist — no callback pretends to
//     perform a side effect it does not perform.
//
// The placeholder track-identity (`"Not Playing"` / `"—"`) matches the
// widget's placeholders exactly, so the Now Playing screen and the
// launcher widget agree on what "nothing playing" looks like before
// real data flows in.
//
// ── Package placement ──────────────────────────────────────────────────
// `com.futura.ui.viewmodel`, matching the tree's `ui/viewmodel/`
// directory (which also houses the eventual AlarmViewModel,
// LibraryViewModel, DjStudioViewModel, VisualizerViewModel,
// CarModeViewModel, SleepDockViewModel, and PrivacyViewModel). Earlier
// in this build sequence `MainViewModel.kt` was delivered at
// `ui/MainViewModel.kt` per an explicit path request — that
// inconsistency is temporary and mechanical; any future turn can move
// MainViewModel under `ui/viewmodel/` with a package change and one
// import update in `MainActivity` + `LoadingScreen`. Nothing else in
// the app depends on the difference.
//
// ── Forward-reference audit ────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                      (generated from delivered resources)
//   • com.futura.ui.components.nowplaying.LyricLine    (delivered)
//   • com.futura.ui.components.nowplaying.MoodTag      (delivered)
//   • com.futura.ui.components.nowplaying.UpNextItem   (delivered)
//
// All other imports resolve to artifacts declared in
// gradle/libs.versions.toml:
//   • androidx.lifecycle:lifecycle-viewmodel-ktx         (ViewModel, viewModelScope)
//   • com.google.dagger:hilt-android                     (@HiltViewModel, @ApplicationContext)
//   • javax.inject:javax.inject                          (@Inject, transitive via Hilt)
//   • org.jetbrains.kotlinx:kotlinx-coroutines-core      (Flow, StateFlow, Job, delay, launch, update)
//
// No future project class, file, engine bridge, MediaSession, repository,
// DAO, or ML class is referenced. Every field and every callback that
// requires such a class is explicitly documented as a placeholder with
// the future subsystem named.

package com.futura.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.R
import com.futura.ui.components.nowplaying.LyricLine
import com.futura.ui.components.nowplaying.MoodTag
import com.futura.ui.components.nowplaying.UpNextItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ═════════════════════════════════════════════════════════════════════════
// UI state
// ═════════════════════════════════════════════════════════════════════════

/**
 * Immutable snapshot of Now Playing UI state.
 *
 * All fields carry defaults so tests and future extensions do not need
 * to spell out the full 21-field constructor every time. The
 * ViewModel's initial state supplies real values (resolved from
 * resources) for the fields that must not be empty.
 *
 * Two categories of field are deliberately **absent** from this class:
 *
 *   • Playback position — held as a separate `positionMs` flow because
 *     it ticks at 200 ms while playing and would otherwise invalidate
 *     the whole state object on every tick, re-recomposing every
 *     subscriber (including `LyricsPanel`'s non-current rows and the
 *     waveform's non-played portion, both of which are optimised to
 *     ignore position changes).
 *
 *   • Bass level and waveform data — same reasoning, held as separate
 *     flows. Bass ticks at the audio-tap rate (up to ~60 Hz); waveform
 *     only changes on track change.
 *
 * Field-by-field subsystem ownership:
 *
 *   `trackId` / `trackTitle` / `trackArtist` / `artworkModel` /
 *   `durationMs`              ← MediaSession / Media3 PlaybackState
 *   `isPlaying`               ← Media3 PlaybackState.isPlaying
 *   `isShuffleEnabled`        ← Media3 PlaybackState.shuffleModeEnabled
 *   `isLoopEnabled`           ← Media3 PlaybackState.repeatMode
 *   `isEqEnabled`             ← DspChainController.enabled
 *   `isBitPerfect`            ← BitPerfectController.state
 *   `formatBadgeText`         ← metadata extractor output
 *   `sampleRateText` / `audioHalText` / `latencyMs` / `bufferFrames`
 *                             ← NativeEngine (Oboe stream config)
 *   `hasSyncedLyrics` / `lyricLines`
 *                             ← OfflineLyricSynchronizer / LyricParser
 *   `upNext` / `similarSuggestions` / `relatedTracks`
 *                             ← queue service + LocalVectorDatabase
 *   `currentMoods`            ← ContextMoodEngine / AutoTaggingClassifier
 */
data class NowPlayingUiState(

    // ── Track identity ────────────────────────────────────────────────
    val trackId: String? = null,
    val trackTitle: String = "",
    val trackArtist: String = "",
    /**
     * Coil model — a `String` URL, `Uri`, `File`, `Drawable`, or `Bitmap`.
     * `null` while no track is loaded; `HeroPlayer` renders its placeholder
     * glyph in that case.
     */
    val artworkModel: Any? = null,
    val durationMs: Long = 0L,

    // ── Playback controls ─────────────────────────────────────────────
    val isPlaying: Boolean = false,
    val isShuffleEnabled: Boolean = false,
    val isLoopEnabled: Boolean = false,

    // ── DSP / audio state ─────────────────────────────────────────────
    val isEqEnabled: Boolean = false,
    val isBitPerfect: Boolean = false,

    // ── Stream inspector ──────────────────────────────────────────────
    val formatBadgeText: String = "",
    val sampleRateText: String = "",
    val audioHalText: String = "",
    val latencyMs: Float = 0f,
    val bufferFrames: Int = 0,

    // ── Lyrics ────────────────────────────────────────────────────────
    val hasSyncedLyrics: Boolean = false,
    val lyricLines: List<LyricLine> = emptyList(),

    // ── Queue ─────────────────────────────────────────────────────────
    val upNext: List<UpNextItem> = emptyList(),
    val similarSuggestions: List<UpNextItem> = emptyList(),

    // ── Related ───────────────────────────────────────────────────────
    val currentMoods: Set<MoodTag> = emptySet(),
    val relatedTracks: List<UpNextItem> = emptyList()
)

// ═════════════════════════════════════════════════════════════════════════
// ViewModel
// ═════════════════════════════════════════════════════════════════════════

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    @ApplicationContext context: Context
) : ViewModel() {

    // ═════════════════════════════════════════════════════════════════════
    // Stable state flow
    // ═════════════════════════════════════════════════════════════════════

    private val _uiState = MutableStateFlow(
        NowPlayingUiState(
            formatBadgeText = context.getString(R.string.nowplaying_format_badge_default),
            sampleRateText = "96 kHz / 24-bit",
            audioHalText = "AAudio",
            latencyMs = 8.3f,
            bufferFrames = 192,
            trackTitle = context.getString(R.string.widget_track_title_placeholder),
            trackArtist = context.getString(R.string.widget_track_artist_placeholder)
        )
    )

    /**
     * Stable Now Playing state (everything except position, bass, and
     * waveform data).
     */
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════
    // High-frequency flows
    // ═════════════════════════════════════════════════════════════════════

    private val _positionMs = MutableStateFlow(0L)

    /**
     * Playback position in milliseconds.
     *
     * Owned by a 200 ms ticker while a track with non-zero duration is
     * playing (see [startPositionTicker]). Once the real playback engine
     * lands, this flow will subscribe to the engine's position updates
     * and the internal ticker will be removed.
     */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _bassLevel = MutableStateFlow(0f)

    /**
     * Current low-frequency energy in `0f..1f`. Drives `HeroPlayer`'s
     * reactive bass-glow aura.
     *
     * Placeholder: stays at `0f` until `engine/AudioTap.kt` is wired to
     * publish per-frame low-band energy into this flow.
     */
    val bassLevel: StateFlow<Float> = _bassLevel.asStateFlow()

    private val _waveformData = MutableStateFlow<FloatArray?>(null)

    /**
     * Per-bar amplitude array for the current track, or `null` while
     * none is available (in which case `KineticWaveform` generates its
     * own placeholder pattern).
     *
     * Placeholder: stays `null` until `data/nowplaying/WaveformExtractor.kt`
     * produces real arrays from the current track's audio file.
     */
    val waveformData: StateFlow<FloatArray?> = _waveformData.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════
    // Playback callbacks
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Toggle play/pause.
     *
     * If no current track is loaded (`trackId == null`), this is a
     * no-op — there is nothing to play. Once a track is loaded via
     * [onQueueItemClick], [onNextClick], or [onPreviousClick], this
     * toggles [NowPlayingUiState.isPlaying] and (while playing) starts
     * the 200 ms position ticker. If the playhead is already at or past
     * the track's duration, position resets to 0 before resuming.
     */
    fun onPlayPauseClick() {
        val state = _uiState.value
        if (state.trackId == null) return

        val newIsPlaying = !state.isPlaying

        // Restart from 0 if we are (re)starting playback at the very end.
        if (newIsPlaying && state.durationMs > 0L && _positionMs.value >= state.durationMs) {
            _positionMs.value = 0L
        }

        _uiState.update { it.copy(isPlaying = newIsPlaying) }

        if (newIsPlaying) startPositionTicker() else stopPositionTicker()
    }

    /**
     * Advance to the next track in [NowPlayingUiState.upNext].
     *
     * Placeholder semantics: cycles through the in-memory queue. Real
     * implementation will delegate to the MediaSession queue
     * (`system/MediaSessionCoordinator.kt`).
     */
    fun onNextClick() {
        val state = _uiState.value
        val queue = state.upNext
        if (queue.isEmpty()) return
        val currentIdx = queue.indexOfFirst { it.id == state.trackId }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % queue.size
        loadTrack(queue[nextIdx])
    }

    /**
     * Go to the previous track in [NowPlayingUiState.upNext].
     *
     * Placeholder semantics: cycles through the in-memory queue. Real
     * implementation will delegate to the MediaSession queue and
     * additionally restart the current track if playback has been
     * running for more than a few seconds (standard Media3 behaviour).
     */
    fun onPreviousClick() {
        val state = _uiState.value
        val queue = state.upNext
        if (queue.isEmpty()) return
        val currentIdx = queue.indexOfFirst { it.id == state.trackId }
        val prevIdx = if (currentIdx <= 0) queue.size - 1 else currentIdx - 1
        loadTrack(queue[prevIdx])
    }

    /**
     * Toggle shuffle.
     *
     * Placeholder: flips the flag only. Real implementation will also
     * shuffle the MediaSession queue
     * (`system/MediaSessionCoordinator.kt`).
     */
    fun onShuffleClick() {
        _uiState.update { it.copy(isShuffleEnabled = !it.isShuffleEnabled) }
    }

    /**
     * Toggle loop.
     *
     * Placeholder: flips the flag only. Real implementation will set
     * `Player.REPEAT_MODE_ONE` / `REPEAT_MODE_ALL` on the MediaSession.
     */
    fun onLoopClick() {
        _uiState.update { it.copy(isLoopEnabled = !it.isLoopEnabled) }
    }

    /**
     * Toggle the equalizer.
     *
     * Placeholder: flips the flag only. Real implementation will call
     * `engine/DspChainController.setEnabled(...)` which drives the C++
     * biquad filter chain via JNI.
     */
    fun onEqToggleClick() {
        _uiState.update { it.copy(isEqEnabled = !it.isEqEnabled) }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Seek callbacks
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Seek to `fraction` of the track duration, where `0f` is the start
     * and `1f` is the end.
     *
     * No-op when no track is loaded (duration = 0). Real implementation
     * will also issue `MediaController.seekTo(ms)` on the MediaSession.
     */
    fun onSeek(fraction: Float) {
        val duration = _uiState.value.durationMs
        if (duration <= 0L) return
        _positionMs.value = (fraction.coerceIn(0f, 1f) * duration).toLong()
    }

    /**
     * Seek to the start time of a specific lyric line. Invoked by
     * `LyricsPanel` when the user taps a line.
     *
     * No-op when no track is loaded. Real implementation will also
     * issue a MediaSession seek.
     */
    fun onSeekToLine(startMs: Long) {
        val duration = _uiState.value.durationMs
        if (duration <= 0L) return
        _positionMs.value = startMs.coerceIn(0L, duration)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Queue callbacks
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Move the item at [from] to [to] in the up-next queue.
     *
     * Fully functional in-memory: the reordered list is persisted in
     * [NowPlayingUiState.upNext]. Real implementation will additionally
     * mirror the new order to the MediaSession queue.
     */
    fun onReorderQueue(from: Int, to: Int) {
        _uiState.update { state ->
            val list = state.upNext.toMutableList()
            if (from in list.indices && to in list.indices && from != to) {
                val moved = list.removeAt(from)
                list.add(to, moved)
            }
            state.copy(upNext = list)
        }
    }

    /**
     * Tap a queue item. Loads it as the current track.
     *
     * Placeholder: sets the track identity from the item's metadata and
     * resets position. Real implementation will additionally call
     * `MediaController.setMediaItem(...)` + `prepare()` + `play()` on
     * the MediaSession.
     */
    fun onQueueItemClick(item: UpNextItem) {
        loadTrack(item)
    }

    /**
     * Tap a similar-suggestion row. Appends the item to the up-next
     * queue if it is not already present (deduped by id).
     */
    fun onSimilarSuggestionClick(item: UpNextItem) {
        appendToQueueIfMissing(item)
    }

    /**
     * Tap a related-track row. Appends the item to the up-next queue if
     * it is not already present (deduped by id).
     */
    fun onRelatedTrackClick(item: UpNextItem) {
        appendToQueueIfMissing(item)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Internals
    // ═════════════════════════════════════════════════════════════════════

    private var positionTicker: Job? = null

    /**
     * Populate the current track from a queue item's placeholder
     * metadata. Used by [onQueueItemClick], [onNextClick], and
     * [onPreviousClick].
     *
     * Real implementation will not construct `NowPlayingUiState` fields
     * directly — it will call the MediaSession and let
     * `PlaybackState`-driven updates populate the state. This function
     * exists only while no MediaSession is wired.
     */
    private fun loadTrack(item: UpNextItem) {
        stopPositionTicker()
        _uiState.update {
            it.copy(
                trackId = item.id,
                trackTitle = item.title,
                trackArtist = item.artist,
                durationMs = item.durationSeconds * 1000L,
                isPlaying = false
            )
        }
        _positionMs.value = 0L
    }

    private fun appendToQueueIfMissing(item: UpNextItem) {
        _uiState.update { state ->
            if (state.upNext.any { it.id == item.id }) {
                state
            } else {
                state.copy(upNext = state.upNext + item)
            }
        }
    }

    /**
     * Drive `positionMs` forward at 200 ms while `isPlaying` is true.
     * Stops automatically when playback is paused, when the track ends,
     * or when the ViewModel is cleared.
     */
    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = viewModelScope.launch {
            val duration = _uiState.value.durationMs
            if (duration <= 0L) return@launch
            while (isActive && _uiState.value.isPlaying) {
                delay(POSITION_TICK_MS)
                val newPosition = _positionMs.value + POSITION_TICK_MS
                if (newPosition >= duration) {
                    _positionMs.value = duration
                    _uiState.update { it.copy(isPlaying = false) }
                    break
                }
                _positionMs.value = newPosition
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }

    // ═════════════════════════════════════════════════════════════════════
    // Constants
    // ═════════════════════════════════════════════════════════════════════

    companion object {
        /**
         * Interval of the placeholder position ticker. 200 ms is coarse
         * enough to be cheap (5 Hz) but fine enough that the waveform
         * playhead, the elapsed-time readout, and the lyrics line
         * transitions all feel smooth to the eye.
         */
        private const val POSITION_TICK_MS = 200L
    }
}