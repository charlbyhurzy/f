// futura-audio/engine/src/main/java/com/futura/engine/NativePlayerBridge.kt
//
// Phase 3 — Native audio player JNI bridge.
//
// ⚠️  PATH NOTE — READ BEFORE EDITING  ⚠️
// The project tree (futura-audio.txt) places this file at
//     app/src/main/java/com/futura/engine/NativePlayerBridge.kt
// under Phase 3 — Native Bridge. This delivery is at
//     engine/src/main/java/com/futura/engine/
// per an explicit instruction, matching NativeEngine.kt's placement two
// turns ago. Since :app already depends on :engine (per
// app/build.gradle.kts), this file is transitively visible to :app; the
// remaining bridges (DspBridge, NativeUsbAudio, NativeFlacScanner,
// NativeWaveform, MlBridge) are still in :app. See NativeEngine.kt's
// path note for the full consolidation story.
//
// ── Responsibility ──────────────────────────────────────────────────────
// Per the tree annotation "✅ 47 methods", this object is the JNI bridge
// for ALL playback control surface: transport, position, volume, pitch,
// speed, A/B loop, gapless queue, source/format introspection,
// bit-perfect toggling, resampler quality, silence trimming, error
// reporting, and diagnostics.
//
// What this file does NOT cover, and why:
//
//   • Engine-wide lifecycle / device info / memory / xrun / priority
//     → NativeEngine.kt (already delivered)
//   • DSP operations (EQ, crossfeed, compression, reverb, …)
//     → DspBridge.kt (23 methods, not yet delivered)
//   • ML inference → MlBridge.kt (delivered, 20 methods)
//   • USB DAC control → NativeUsbAudio.kt (not yet delivered)
//   • FLAC frame recovery → NativeFlacScanner.kt (not yet delivered)
//   • Waveform peaks → NativeWaveform.kt (not yet delivered)
//   • Audio taps for visualizers → AudioTap / LockFreeRingBufferTap
//
// The 47 methods are grouped by domain in the source; the group order
// mirrors the JNI dispatch order in JniBridge.cpp for easier native-side
// review:
//
//   1. Lifecycle (5)         — create, destroy, prepare, reset, isPrepared
//   2. Transport (4)         — play, pause, stop, seekTo
//   3. Playback state (5)    — isPlaying, position, duration, states
//   4. Volume (3)            — linear, dB, get
//   5. Speed / pitch (4)     — set/get each
//   6. A/B loop (4)          — set, clear, enable, isEnabled
//   7. Gapless queue (5)     — enqueue, clear, size, skip, isReady
//   8. Source info (4)       — format, sample rate, channels, bit depth
//   9. Bit-perfect/resampler (4) — enabled, quality
//  10. Silence trim (2)      — set, is
//  11. Error state (2)       — last error, clear
//  12. Diagnostics (5)       — buffered, loaded, output rate/channels,
//                              render thread id
//
// ── Design contract (identical to NativeEngine.kt and MlBridge.kt) ──────
// Every external method is `private` and wrapped by a public method that:
//   1. Checks `libraryAvailable` — the .so may not exist yet.
//   2. Catches `UnsatisfiedLinkError` — thrown when an `external fun`
//      has no matching native implementation. Happens today because
//      engine/src/main/cpp/jni/JniBridge.cpp has not been authored yet.
//   3. Catches `Exception` — any other native failure.
//
// On failure, wrappers return documented defaults:
//   • Boolean-returning   → false
//   • Int-returning       → 0 (or -1 for "unknown" semantics)
//   • Long-returning      → 0 (or -1 for "unknown" semantics)
//   • Float-returning     → 0f (or Float.NaN for "unknown" semantics)
//   • String-returning    → "" (empty)
//   • Void-returning      → no-op
//
// ── Threading contract ─────────────────────────────────────────────────
// All public methods are synchronous and blocking. The methods that MUST
// be called off the main thread are:
//
//   • prepare() — decodes the file header and opens the decoder context
//     (5–200 ms depending on format and storage speed).
//   • enqueueNext() — opens the next file's decoder context (same cost
//     as prepare).
//
// All other methods are cheap (single native call, sub-millisecond on
// the JNI boundary), but for consistency the whole bridge should be
// driven from a background coroutine. The natural caller is
// `system/MediaSessionCoordinator.kt` (not yet delivered), which
// receives Media3 callbacks on the main thread and dispatches to the
// engine on a dedicated audio-control thread.
//
// ── JNI naming contract (for JniBridge.cpp) ─────────────────────────────
// This object is `object NativePlayerBridge` in package
// `com.futura.engine`. The JNI functions in JniBridge.cpp must be
// declared with the exact names and signatures below. The `thiz`
// parameter is a `jobject` (the singleton instance), NOT a `jclass`:
//
//     extern "C" JNIEXPORT jboolean JNICALL
//     Java_com_futura_engine_NativePlayerBridge_nativeCreate(
//         JNIEnv* env, jobject thiz);
//
// (If the native side uses RegisterNatives in JNI_OnLoad, signatures
// must match the Kotlin declarations exactly.)
//
// ── Forward-reference audit ──────────────────────────────────────────
// Imported symbols:
//   • android.util.Log       (framework, all API levels)
//   • kotlin.jvm.Volatile    (Kotlin stdlib annotation)
//
// No project class, no Compose, no Hilt, no coroutines, no MediaSession,
// no NativeEngine (deliberately, to keep the bridges independent), no
// MlBridge, no DspBridge, no AudioTap, no undelivered data classes.
// This file compiles and functions standalone against the entire
// delivered codebase.

package com.futura.engine

import android.util.Log

// ═════════════════════════════════════════════════════════════════════════
// State enums
// ═════════════════════════════════════════════════════════════════════════

/**
 * High-level playback state. Mirrors Media3's `Player.STATE_*` constants
 * so `MediaSessionCoordinator` (when delivered) can map 1:1 without an
 * intermediate representation.
 *
 * Values are stable across releases — the native side and Kotlin side
 * both refer to them by integer, so reordering this enum requires a
 * coordinated change in JniBridge.cpp.
 */
enum class NativePlaybackState(val nativeValue: Int) {
    /** Not prepared — no source loaded or prepare() has not completed. */
    IDLE(1),

    /** Source is prepared and ready; play() will begin audio. */
    READY(2),

    /** Playing audio. */
    PLAYING(3),

    /** Paused; audio output is suspended but the decoder is kept warm. */
    PAUSED(4),

    /** Playback has reached the end of the current source. */
    ENDED(5),

    /** An error occurred; check getLastError() for details. */
    ERROR(6);

    companion object {
        fun fromNative(value: Int): NativePlaybackState =
            entries.firstOrNull { it.nativeValue == value } ?: IDLE
    }
}

/**
 * Internal stream state machine — the value the C++ `StreamStateMachine.h`
 * holds. Exposed because the diagnostics / Stream Inspector surface
 * displays it, and because tests assert against specific states during
 * device-change scenarios (headphone unplug, Bluetooth reconnection).
 *
 * Distinct from [NativePlaybackState]: the playback state is the
 * user-facing "is it playing?" abstraction; the stream state is the
 * lower-level "what is the OS audio stream doing right now?" state.
 * During a device switch, playback state may briefly be PLAYING while
 * stream state is STREAM_FLUSHING, because the user's intent (playback)
 * is unchanged while the underlying stream is being reconfigured.
 */
enum class NativeStreamState(val nativeValue: Int) {
    UNINITIALIZED(0),
    STREAM_OPENING(1),
    STREAM_STARTED(2),
    STREAM_PAUSED(3),
    STREAM_FLUSHING(4),
    STREAM_STOPPED(5),
    STREAM_CLOSING(6),
    STREAM_DISCONNECTED(7),
    STREAM_ERROR(8);

    companion object {
        fun fromNative(value: Int): NativeStreamState =
            entries.firstOrNull { it.nativeValue == value } ?: UNINITIALIZED
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Source type hint
// ═════════════════════════════════════════════════════════════════════════

/**
 * Where a URI points. Passed to [NativePlayerBridge.prepare] and
 * [NativePlayerBridge.enqueueNext] so the native decoder layer knows
 * whether to open a filesystem path, a content resolver stream, or an
 * app asset.
 *
 * Values are stable — changing them requires a coordinated update to
 * JniBridge.cpp.
 */
enum class SourceType(val nativeValue: Int) {
    /** Filesystem path — `file://` URIs or plain absolute paths. */
    FILE(0),

    /** Content resolver URI (MediaStore, SAF). Requires file descriptor
     *  resolution through `ContentResolver.openFileDescriptor()` on the
     *  Kotlin side; the native side receives the resolved fd directly
     *  once that plumbing is added. For now the native side treats this
     *  as a hint that the URI is `content://`. */
    CONTENT_URI(1),

    /** App asset — `assets/…`. Native side resolves via AAssetManager. */
    ASSET(2)
}

// ═════════════════════════════════════════════════════════════════════════
// Resampler quality
// ═════════════════════════════════════════════════════════════════════════

/**
 * Quality preset for the resampler (feature #59 — `libsamplerate`).
 * Higher quality costs more CPU; the defaults are tuned so that normal
 * playback on a modern phone uses [BALANCED].
 */
enum class ResamplerQuality(val nativeValue: Int) {
    /** Nearest-neighbour — cheapest, audible artefacts. Rarely useful. */
    LOW(0),

    /** libsamplerate `SRC_SINC_FASTEST` — good balance for mobile. */
    BALANCED(1),

    /** libsamplerate `SRC_SINC_MEDIUM_QUALITY` — better, more CPU. */
    HIGH(2),

    /** libsamplerate `SRC_SINC_BEST_QUALITY` — near-studio, highest CPU. */
    BEST(3)
}

// ═════════════════════════════════════════════════════════════════════════
// Bridge
// ═════════════════════════════════════════════════════════════════════════

/**
 * The 47-method JNI bridge to the native audio player.
 *
 * See the file header for the full design contract (safety-first
 * wrappers, threading, JNI naming, path notes). All methods are
 * synchronous; see the threading section before calling any of them
 * from a hot path.
 */
object NativePlayerBridge {

    private const val TAG = "NativePlayerBridge"
    private const val LIBRARY_NAME = "futura_engine"

    @Volatile
    private var libraryAvailable: Boolean = false

    init {
        libraryAvailable = try {
            System.loadLibrary(LIBRARY_NAME)
            Log.i(TAG, "Native library '$LIBRARY_NAME' loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(
                TAG,
                "Native library '$LIBRARY_NAME' unavailable — playback disabled. " +
                    "Build :engine or check ABI filters if this is unexpected.",
                e
            )
            false
        }
    }

    /**
     * True if `libfutura_engine.so` loaded into the process. False means
     * no player functionality is available — `MediaSessionCoordinator`
     * should fall back to Media3's default `ExoPlayer` for playback.
     */
    fun isAvailable(): Boolean = libraryAvailable

    // ═════════════════════════════════════════════════════════════════
    // 1. Lifecycle (5)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Create the native player instance. Must be called before any other
     * method on this bridge.
     *
     * Idempotent: calling twice returns true without creating a second
     * instance. The single instance is process-wide; multiple
     * simultaneous players are not supported (and not needed — Futura is
     * a single-stream music player).
     *
     * @return true on success, false if library unavailable or native
     *         allocation failed.
     */
    fun create(): Boolean = safeCall(false) { nativeCreate() }

    /**
     * Destroy the native player instance and release all decoder,
     * resampler, and buffer resources. Safe to call multiple times.
     */
    fun destroy() {
        safeCallUnit { nativeDestroy() }
    }

    /**
     * Prepare a source for playback.
     *
     * Blocking — decodes the header and opens the decoder context (5–200
     * ms). **Call off the main thread.**
     *
     * @param uri Filesystem path, `content://` URI, or asset path
     *        (interpreted according to [sourceType]).
     * @param sourceType How to resolve [uri]. See [SourceType].
     * @return true on success, false on any failure (file not found,
     *         unsupported format, decode error).
     */
    fun prepare(uri: String, sourceType: SourceType): Boolean =
        safeCall(false) { nativePrepare(uri, sourceType.nativeValue) }

    /**
     * Reset to the IDLE state. Flushes the decoder, empties the queue,
     * and stops audio output. Equivalent to `stop() + clearQueue()` but
     * preserves the native player instance (no re-allocation).
     */
    fun reset() {
        safeCallUnit { nativeReset() }
    }

    /**
     * True if a source is currently prepared (state ≥ READY).
     * Distinguishes "prepared but paused" from "nothing loaded" without
     * querying the enum.
     */
    fun isPrepared(): Boolean = safeCall(false) { nativeIsPrepared() }

    // ═════════════════════════════════════════════════════════════════
    // 2. Transport (4)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Begin or resume playback. Audio focus must already be granted by
     * the caller (the engine does not request focus itself).
     *
     * @return true on success, false if not prepared or output is
     *         unavailable.
     */
    fun play(): Boolean = safeCall(false) { nativePlay() }

    /**
     * Pause playback. Keeps the decoder context warm so a subsequent
     * `play()` resumes instantly.
     */
    fun pause(): Boolean = safeCall(false) { nativePause() }

    /**
     * Stop playback and reset the playhead to position 0.
     */
    fun stop(): Boolean = safeCall(false) { nativeStop() }

    /**
     * Seek to [positionMs] within the current source.
     *
     * The seek is asynchronous: this method returns as soon as the seek
     * request is queued. The playhead actually reaches the target within
     * one render frame (typically ~10 ms). Callers polling position
     * should expect it to lag by up to one buffer.
     *
     * @param positionMs Target position in milliseconds. Values beyond
     *        duration are clamped to duration by the native side.
     * @return true if the seek was accepted, false if not prepared.
     */
    fun seekTo(positionMs: Long): Boolean =
        safeCall(false) { nativeSeekTo(positionMs) }

    // ═════════════════════════════════════════════════════════════════
    // 3. Playback state (5)
    // ═════════════════════════════════════════════════════════════════

    /**
     * True if the player is actively playing. Equivalent to
     * `getPlaybackState() == PLAYING`.
     */
    fun isPlaying(): Boolean = safeCall(false) { nativeIsPlaying() }

    /**
     * Current playhead position in milliseconds. Includes paused time —
     * the position does not advance while paused but is retained.
     */
    fun getPositionMs(): Long = safeCall(0L) { nativeGetPositionMs() }

    /**
     * Total duration of the current source in milliseconds. Returns
     * `-1L` when duration is not known (live streams, corrupt headers).
     */
    fun getDurationMs(): Long = safeCall(-1L) { nativeGetDurationMs() }

    /**
     * Current high-level playback state. See [NativePlaybackState].
     */
    fun getPlaybackState(): NativePlaybackState =
        NativePlaybackState.fromNative(safeCall(1) { nativeGetPlaybackState() })

    /**
     * Current low-level stream state. See [NativeStreamState]. Useful
     * for the Stream Inspector's "STREAM_STARTED" / "STREAM_FLUSHING"
     * transitions during device changes.
     */
    fun getStreamState(): NativeStreamState =
        NativeStreamState.fromNative(safeCall(0) { nativeGetStreamState() })

    // ═════════════════════════════════════════════════════════════════
    // 4. Volume (3)
    // ═════════════════════════════════════════════════════════════════
    //
    // Two volume representations are exposed because two subsystems need
    // different units:
    //
    //   • Linear (0f..1f) is what UI sliders use (VolumeSlider value,
    //     hardware key mapping).
    //   • dB (-96f..0f) is what ReplayGain, LUFS normalisation, and the
    //     DSP crossfader compute in.
    //
    // The native side keeps them in sync internally: setting linear
    // recomputes dB and vice versa. Reading either returns the last
    // value set through either path.

    /**
     * Set output volume on a linear scale, `0f` (silent) to `1f` (unity
     * gain). Values outside the range are clamped.
     */
    fun setVolumeLinear(volume: Float) {
        safeCallUnit { nativeSetVolumeLinear(volume.coerceIn(0f, 1f)) }
    }

    /**
     * Set output volume in decibels. `0f` is unity gain; `-96f` is
     * practical silence (native side clamps below this to mute). Positive
     * values are clamped to 0 dB by the native limiter — this method
     * does NOT bypass the LoudnessMaximizer safety.
     */
    fun setVolumeDb(db: Float) {
        safeCallUnit { nativeSetVolumeDb(db.coerceIn(-96f, 0f)) }
    }

    /**
     * Current output volume on a linear scale.
     */
    fun getVolumeLinear(): Float = safeCall(0f) { nativeGetVolumeLinear() }

    // ═════════════════════════════════════════════════════════════════
    // 5. Speed / pitch (4)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Set playback speed multiplier. `1f` is normal, `0.5f` is half
     * speed, `2f` is double speed. Supported range is `[0.5f, 3.0f]` —
     * values outside this are clamped. Speed change does not affect
     * pitch (that is the pitch-preserving engine, feature #100).
     *
     * @return true if accepted, false if the engine is not prepared.
     */
    fun setPlaybackSpeed(speed: Float): Boolean =
        safeCall(false) { nativeSetPlaybackSpeed(speed.coerceIn(0.5f, 3.0f)) }

    /**
     * Current playback speed multiplier.
     */
    fun getPlaybackSpeed(): Float = safeCall(1f) { nativeGetPlaybackSpeed() }

    /**
     * Set pitch in semitones. `0f` is no shift, `+12f` is one octave up,
     * `-12f` is one octave down. Supported range is `[-24f, +24f]`.
     * Pitch change does not affect speed (that is the pitch-shifting
     * engine, feature #69 — SoundTouch/native phase vocoder).
     *
     * @return true if accepted, false if the engine is not prepared.
     */
    fun setPitchSemitones(semitones: Float): Boolean =
        safeCall(false) { nativeSetPitchSemitones(semitones.coerceIn(-24f, 24f)) }

    /**
     * Current pitch offset in semitones.
     */
    fun getPitchSemitones(): Float = safeCall(0f) { nativeGetPitchSemitones() }

    // ═════════════════════════════════════════════════════════════════
    // 6. A/B loop (4)
    // ═════════════════════════════════════════════════════════════════
    //
    // Feature #98 — A/B Loop Engine. Frame-accurate start and end
    // markers for repeating a section of a track. When a loop is active
    // and enabled, playback wraps from `endMs` back to `startMs` instead
    // of advancing past the end.

    /**
     * Set the A and B loop points. Does NOT enable the loop —
     * `setLoopEnabled(true)` must also be called. Setting the loop
     * points while the loop is enabled immediately applies the new
     * boundaries.
     *
     * @param startMs A point (loop start) in milliseconds.
     * @param endMs B point (loop end) in milliseconds. Must be greater
     *        than `startMs` and less than the track duration; values
     *        outside these constraints cause the method to return false.
     * @return true on success, false if parameters are invalid.
     */
    fun setLoopAB(startMs: Long, endMs: Long): Boolean {
        if (endMs <= startMs) return false
        return safeCall(false) { nativeSetLoopAB(startMs, endMs) }
    }

    /**
     * Clear the A/B loop points. Equivalent to disabling the loop AND
     * forgetting the points — a subsequent `setLoopEnabled(true)` will
     * have no effect until new points are set.
     */
    fun clearLoopAB() {
        safeCallUnit { nativeClearLoopAB() }
    }

    /**
     * Enable or disable the loop. Requires valid A/B points to have been
     * set via `setLoopAB()`; calling this without prior points is a no-op.
     */
    fun setLoopEnabled(enabled: Boolean) {
        safeCallUnit { nativeSetLoopEnabled(enabled) }
    }

    /**
     * True if the A/B loop is currently enabled AND has valid points.
     */
    fun isLoopEnabled(): Boolean = safeCall(false) { nativeIsLoopEnabled() }

    // ═════════════════════════════════════════════════════════════════
    // 7. Gapless queue (5)
    // ═════════════════════════════════════════════════════════════════
    //
    // Feature #67 — Gapless Playback Queue. The native side pre-decodes
    // the next track while the current track plays, so the transition
    // is truly gapless (no silence, no click, no waiting for the decoder
    // to seek). The queue holds at most ONE pre-decoded track at a time;
    // enqueuing a second track while one is already pre-decoded
    // replaces it.

    /**
     * Enqueue the next track for gapless playback.
     *
     * Blocking — opens the next file's decoder context (5–200 ms).
     * **Call off the main thread.**
     *
     * If a track is already enqueued, it is replaced. The pre-decoded
     * audio for the previous enqueue is discarded.
     *
     * @param uri Next track's URI (interpreted according to [sourceType]).
     * @param sourceType How to resolve [uri].
     * @return true on success, false on any failure.
     */
    fun enqueueNext(uri: String, sourceType: SourceType): Boolean =
        safeCall(false) { nativeEnqueueNext(uri, sourceType.nativeValue) }

    /**
     * Clear the gapless queue without affecting current playback. The
     * currently-playing track continues; the pre-decoded next track is
     * discarded.
     */
    fun clearQueue() {
        safeCallUnit { nativeClearQueue() }
    }

    /**
     * Number of tracks currently enqueued for gapless transition. `0`
     * or `1` — the queue holds at most one pre-decoded track.
     */
    fun getQueueSize(): Int = safeCall(0) { nativeGetQueueSize() }

    /**
     * Skip to the next track. If a track is pre-decoded, playback
     * transitions seamlessly; otherwise this returns false.
     *
     * Unlike `seekTo(duration)`, this call advances the queue rather
     * than waiting for the current track to end. Useful for "next"
     * hardware button handling where the user wants the transition NOW.
     *
     * @return true on success, false if no track is queued.
     */
    fun skipToNext(): Boolean = safeCall(false) { nativeSkipToNext() }

    /**
     * True if the next track has been fully pre-decoded and is ready
     * for a truly seamless transition. When false, `skipToNext()` will
     * still work but may introduce a small gap while the decoder
     * prepares.
     */
    fun isNextPrepared(): Boolean = safeCall(false) { nativeIsNextPrepared() }

    // ═════════════════════════════════════════════════════════════════
    // 8. Source info (4)
    // ═════════════════════════════════════════════════════════════════
    //
    // These describe the CURRENTLY-LOADED source. Used by the Stream
    // Inspector modal and by the format badge in NowPlayingScreen's
    // TopBar. Values reflect the source file as decoded — they are NOT
    // affected by resampling (which happens after decoding).

    /**
     * Decoded source format string, e.g. `"FLAC"`, `"MP3"`, `"AAC"`,
     * `"ALAC"`. Returns `""` (empty string) if no source is loaded or
     * on failure — callers should check `isPrepared()` first.
     */
    fun getSourceFormat(): String =
        safeCall("") { nativeGetSourceFormat() }

    /**
     * Decoded source sample rate in Hz (e.g. `44100`, `48000`, `96000`).
     * Returns `0` if no source is loaded.
     */
    fun getSourceSampleRate(): Int = safeCall(0) { nativeGetSourceSampleRate() }

    /**
     * Decoded source channel count (`1` = mono, `2` = stereo,
     * `6` = 5.1). Returns `0` if no source is loaded.
     */
    fun getSourceChannels(): Int = safeCall(0) { nativeGetSourceChannels() }

    /**
     * Decoded source bit depth (`16`, `24`, `32`). Returns `0` if the
     * source is lossy (MP3, AAC, Opus) or no source is loaded.
     */
    fun getSourceBitDepth(): Int = safeCall(0) { nativeGetSourceBitDepth() }

    // ═════════════════════════════════════════════════════════════════
    // 9. Bit-perfect / resampler (4)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enable or disable bit-perfect playback (feature #142).
     *
     * When enabled, the engine bypasses the resampler and requests the
     * device's native sample rate. When disabled, the resampler runs at
     * `getResamplerQuality()` if the source rate differs from the
     * device's output rate.
     *
     * Bit-perfect playback requires: (a) an output route that supports
     * the source rate (USB DAC, wired headphone with good DAC, some
     * Bluetooth codecs), (b) `enableBitPerfect = true` in the
     * NativeEngineConfig at initialize time. If either is missing, this
     * method still returns true (the setting is recorded) but effective
     * bit-perfect playback will not engage; check `isBitPerfectEnabled()`
     * for the recorded setting vs. the effective state via the future
     * StreamInspector.
     *
     * @return true if the setting was applied, false if the current
     *         output route cannot support it.
     */
    fun setBitPerfectEnabled(enabled: Boolean): Boolean =
        safeCall(false) { nativeSetBitPerfectEnabled(enabled) }

    /**
     * True if bit-perfect mode is currently enabled. Reflects the last
     * setting applied via [setBitPerfectEnabled], NOT the effective
     * state — the device may not be delivering bit-perfect audio even
     * with this flag true (e.g. the OS mixer intercepts output).
     */
    fun isBitPerfectEnabled(): Boolean =
        safeCall(false) { nativeIsBitPerfectEnabled() }

    /**
     * Set the resampler quality preset. See [ResamplerQuality].
     * Takes effect on the NEXT stream configuration (which happens
     * automatically if the source rate and output rate differ, or
     * manually by disabling/enabling bit-perfect mode).
     */
    fun setResamplerQuality(quality: ResamplerQuality) {
        safeCallUnit { nativeSetResamplerQuality(quality.nativeValue) }
    }

    /**
     * Current resampler quality preset.
     */
    fun getResamplerQuality(): ResamplerQuality {
        val raw = safeCall(1) { nativeGetResamplerQuality() }
        return ResamplerQuality.entries.firstOrNull { it.nativeValue == raw }
            ?: ResamplerQuality.BALANCED
    }

    // ═════════════════════════════════════════════════════════════════
    // 10. Silence trimming (2)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enable or disable the silence trimmer (feature #99). When enabled,
     * the engine scans the start and end of each track for silence and
     * skips over it — reducing perceived latency between tracks in a
     * gapless queue and eliminating the "quiet pause before the song
     * starts" complaint common to lossless albums.
     *
     * The scanner uses a fixed threshold (-40 dB for at least 200 ms).
     * Configuration of the threshold is not exposed at the JNI boundary
     * because tuning it is an edge case; the defaults work well for
     * every track we've tested.
     */
    fun setSilenceTrimEnabled(enabled: Boolean) {
        safeCallUnit { nativeSetSilenceTrimEnabled(enabled) }
    }

    /**
     * True if silence trimming is currently enabled.
     */
    fun isSilenceTrimEnabled(): Boolean =
        safeCall(false) { nativeIsSilenceTrimEnabled() }

    // ═════════════════════════════════════════════════════════════════
    // 11. Error state (2)
    // ═════════════════════════════════════════════════════════════════

    /**
     * The most recent error reported by the native layer, as a
     * human-readable string. Empty if no error has occurred since the
     * last successful operation or the last `clearLastError()`.
     *
     * Used by `MediaSessionCoordinator` to surface playback errors to
     * the UI. Includes the C++ exception's `what()` when the native
     * side has one, or a generic descriptive message otherwise.
     */
    fun getLastError(): String = safeCall("") { nativeGetLastError() }

    /**
     * Clear the last-error state. Called after the caller has handled
     * or logged the error so a subsequent `getLastError()` returns
     * empty rather than the stale error.
     */
    fun clearLastError() {
        safeCallUnit { nativeClearLastError() }
    }

    // ═════════════════════════════════════════════════════════════════
    // 12. Diagnostics (5)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Position up to which audio has been buffered, in milliseconds.
     * Always ≥ `getPositionMs()`; the gap between them is the
     * buffering-ahead distance. When this equals `getPositionMs()` the
     * player has exhausted its buffer and is about to underrun.
     */
    fun getBufferedPositionMs(): Long =
        safeCall(0L) { nativeGetBufferedPositionMs() }

    /**
     * Number of milliseconds of audio loaded into the decoder so far.
     * For local files this equals the duration after preparation. For
     * streams it advances as data arrives.
     */
    fun getLoadedMs(): Long = safeCall(0L) { nativeGetLoadedMs() }

    /**
     * Actual output sample rate, in Hz. May differ from
     * `getSourceSampleRate()` when resampling is active. This is the
     * rate the audio device is being driven at, which is what the
     * Stream Inspector displays.
     */
    fun getOutputSampleRate(): Int = safeCall(0) { nativeGetOutputSampleRate() }

    /**
     * Actual output channel count. Matches the source's channels for
     * stereo/mono sources; may be reduced when the output route has
     * fewer channels than a multichannel source.
     */
    fun getOutputChannels(): Int = safeCall(0) { nativeGetOutputChannels() }

    /**
     * The OS thread ID of the native audio render thread. Returns `-1L`
     * until the render thread has started (after `play()` first
     * transitions to PLAYING).
     *
     * Useful for:
     *   • `NativeEngine.getCurrentAudioThreadPriority()` — callers can
     *     verify the audio thread actually has SCHED_FIFO.
     *   • Debuggers that want to attach to the correct thread.
     *   • Logs that want to correlate render events across threads.
     */
    fun getRenderThreadId(): Long = safeCall(-1L) { nativeGetRenderThreadId() }

    // ═════════════════════════════════════════════════════════════════
    // Safe-call helpers (identical pattern to NativeEngine.kt / MlBridge.kt)
    // ═════════════════════════════════════════════════════════════════

    private fun <T> safeCall(default: T, block: () -> T): T {
        if (!libraryAvailable) return default
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — JniBridge.cpp likely not compiled", e)
            default
        } catch (e: Exception) {
            Log.e(TAG, "Native call failed", e)
            default
        }
    }

    private fun safeCallUnit(block: () -> Unit) {
        if (!libraryAvailable) return
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — JniBridge.cpp likely not compiled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Native call failed", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Native declarations — 47 total
    // ═════════════════════════════════════════════════════════════════
    //
    // Private — callers cannot bypass the safe wrappers. JNI ignores
    // Kotlin visibility. `thiz` on the native side is a `jobject`
    // (singleton instance), NOT a `jclass`.

    // ── 1. Lifecycle (5) ─────────────────────────────────────────────
    private external fun nativeCreate(): Boolean
    private external fun nativeDestroy()
    private external fun nativePrepare(uri: String, sourceType: Int): Boolean
    private external fun nativeReset()
    private external fun nativeIsPrepared(): Boolean

    // ── 2. Transport (4) ─────────────────────────────────────────────
    private external fun nativePlay(): Boolean
    private external fun nativePause(): Boolean
    private external fun nativeStop(): Boolean
    private external fun nativeSeekTo(positionMs: Long): Boolean

    // ── 3. Playback state (5) ────────────────────────────────────────
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeGetPositionMs(): Long
    private external fun nativeGetDurationMs(): Long
    private external fun nativeGetPlaybackState(): Int
    private external fun nativeGetStreamState(): Int

    // ── 4. Volume (3) ────────────────────────────────────────────────
    private external fun nativeSetVolumeLinear(volume: Float)
    private external fun nativeSetVolumeDb(db: Float)
    private external fun nativeGetVolumeLinear(): Float

    // ── 5. Speed / pitch (4) ─────────────────────────────────────────
    private external fun nativeSetPlaybackSpeed(speed: Float): Boolean
    private external fun nativeGetPlaybackSpeed(): Float
    private external fun nativeSetPitchSemitones(semitones: Float): Boolean
    private external fun nativeGetPitchSemitones(): Float

    // ── 6. A/B loop (4) ──────────────────────────────────────────────
    private external fun nativeSetLoopAB(startMs: Long, endMs: Long): Boolean
    private external fun nativeClearLoopAB()
    private external fun nativeSetLoopEnabled(enabled: Boolean)
    private external fun nativeIsLoopEnabled(): Boolean

    // ── 7. Gapless queue (5) ─────────────────────────────────────────
    private external fun nativeEnqueueNext(uri: String, sourceType: Int): Boolean
    private external fun nativeClearQueue()
    private external fun nativeGetQueueSize(): Int
    private external fun nativeSkipToNext(): Boolean
    private external fun nativeIsNextPrepared(): Boolean

    // ── 8. Source info (4) ───────────────────────────────────────────
    private external fun nativeGetSourceFormat(): String
    private external fun nativeGetSourceSampleRate(): Int
    private external fun nativeGetSourceChannels(): Int
    private external fun nativeGetSourceBitDepth(): Int

    // ── 9. Bit-perfect / resampler (4) ───────────────────────────────
    private external fun nativeSetBitPerfectEnabled(enabled: Boolean): Boolean
    private external fun nativeIsBitPerfectEnabled(): Boolean
    private external fun nativeSetResamplerQuality(quality: Int)
    private external fun nativeGetResamplerQuality(): Int

    // ── 10. Silence trimming (2) ─────────────────────────────────────
    private external fun nativeSetSilenceTrimEnabled(enabled: Boolean)
    private external fun nativeIsSilenceTrimEnabled(): Boolean

    // ── 11. Error state (2) ──────────────────────────────────────────
    private external fun nativeGetLastError(): String
    private external fun nativeClearLastError()

    // ── 12. Diagnostics (5) ──────────────────────────────────────────
    private external fun nativeGetBufferedPositionMs(): Long
    private external fun nativeGetLoadedMs(): Long
    private external fun nativeGetOutputSampleRate(): Int
    private external fun nativeGetOutputChannels(): Int
    private external fun nativeGetRenderThreadId(): Long
}