// futura-audio/engine/src/main/java/com/futura/engine/DspBridge.kt
//
// Phase 4 — DSP processing JNI bridge.
//
// ⚠️  PATH NOTE — READ BEFORE EDITING  ⚠️
// The project tree (futura-audio.txt) places this file at
//     app/src/main/java/com/futura/engine/DspBridge.kt
// under Phase 4 — DSP Kotlin. This delivery is at
//     engine/src/main/java/com/futura/engine/
// per an explicit instruction, matching NativeEngine.kt and
// NativePlayerBridge.kt. The higher-level Kotlin wrapper
// (DspChainController.kt, per the tree) remains in :app and consumes
// this bridge. `:app → :engine` dependency direction is preserved
// (already declared in app/build.gradle.kts). See NativeEngine.kt's
// path note for the full consolidation story.
//
// ── Responsibility ──────────────────────────────────────────────────────
// Per the tree annotation "✅ 23 external methods", this object is the
// JNI bridge for the DSP chain. It exposes:
//
//   • Master enable/disable of the whole DSP pipeline.
//   • Atomic bulk configuration (apply / read the full chain config).
//   • Fast-path per-band EQ and per-parameter setters (no data-class
//     allocation, single JNI call).
//   • Preset management (apply by name, save, list).
//   • Analysis (FFT spectrum, ReplayGain LUFS).
//   • Metering (per-channel peak, true-peak in dBFS).
//
// What this file deliberately does NOT cover, and where those live:
//
//   • Engine lifecycle, device info, memory, xrun, priority
//       → NativeEngine.kt (delivered)
//   • Playback transport, source info, gapless queue
//       → NativePlayerBridge.kt (delivered)
//   • ML inference
//       → MlBridge.kt (delivered)
//   • USB DAC, FLAC recovery, waveform, audio taps
//       → NativeUsbAudio / NativeFlacScanner / NativeWaveform / AudioTap
//         (not yet delivered)
//
// ── Coverage of the 26 DSP C++ headers ─────────────────────────────────
// The tree lists 26 headers under engine/src/main/cpp/dsp/. 23 JNI
// methods cover them as follows:
//
//   Header                          | Covered by
//   --------------------------------|------------------------------------
//   GraphicEq10Band.h               | setEqBandGain, getEqBandGain
//   ParametricFilterDesign.h        | (used internally by GraphicEq10Band)
//   Biquad.h                        | (internal building block)
//   ReplayGainAnalyzer.h            | analyzeReplayGain
//   PreampGainController.h          | setPreampGain, getPreampGain
//   FFTAnalyzer.h                   | getSpectrumData
//   SpectrumSmoother.h              | (called by FFTAnalyzer internally)
//   CrossfeedProcessor.h            | setCrossfeedEnabled (+applyConfig)
//   DynamicRangeCompressor.h        | setCompressorEnabled, setCompressorParams
//   StereoWidthExpander.h           | setStereoWidth
//   BassBoostSynthesizer.h          | setBassBoost
//   FreeverbEngine.h                | setReverbEnabled, setReverbParams
//   LoudnessMaximizer.h             | (via applyConfig — always-on safety)
//   DitherGenerator.h               | (internal, applied before output)
//   PeakLevelDetector.h             | getPeakLevels
//   PhaseInverter.h                 | (via applyConfig)
//   DcOffsetFilter.h                | (internal, always-on)
//   CrossfaderPipeline.h            | (via applyConfig)
//   AutomaticVolumeLimiter.h        | (internal, always-on safety)
//   DspPresetStore.h                | applyPreset, savePreset, listPresets
//   AutoGainControlEngine.h         | (via applyConfig)
//   TruePeakMeteringProcessor.h     | getTruePeakDb
//   StereoToBinauralConverter.h     | setBinauralEnabled
//   HighPassDcBlockingFilter.h      | (internal, always-on)
//   ReverbTailFlusher.h             | (internal, called by FreeverbEngine)
//   SilencePaddingEngine.h          | (internal, called by gapless engine)
//
// applyConfig / getConfig are the two methods that touch the whole
// chain — they let a preset load be atomic (no glitch from an
// intermediate state) and give the Stream Inspector a single call to
// snapshot every parameter.
//
// ── Bulk-config encoding ────────────────────────────────────────────────
// `getConfig()` returns a `DspChainConfig?` reconstructed from a
// `FloatArray` layout. `applyConfig()` encodes to the same layout. The
// 30-float buffer is the interchange format between Kotlin and C++ and
// is documented here so JniBridge.cpp can decode it unambiguously:
//
//   Index | Field
//   ------|----------------------------------
//   0     | enabled                     (0.0 = false, 1.0 = true)
//   1     | preampDb
//   2..11 | eqBandGainsDb[0..9]
//   12    | bassBoost
//   13    | stereoWidth
//   14    | crossfeedEnabled            (0/1)
//   15    | crossfeedAmount
//   16    | binauralEnabled             (0/1)
//   17    | reverbEnabled               (0/1)
//   18    | reverbRoomSize
//   19    | reverbDamping
//   20    | reverbWetLevel
//   21    | compressorEnabled           (0/1)
//   22    | compressorThresholdDb
//   23    | compressorRatio
//   24    | compressorAttackMs
//   25    | compressorReleaseMs
//   26    | autoGainControlEnabled      (0/1)
//   27    | loudnessMaximizerEnabled    (0/1)
//   28    | phaseInvertedLeft           (0/1)
//   29    | phaseInvertedRight          (0/1)
//
// Total: 30 floats. Both sides must agree on this layout; changing it
// requires a coordinated update here and in JniBridge.cpp.
//
// ── Design contract (identical to NativeEngine/NativePlayerBridge/MlBridge) ─
// Every external method is `private` and wrapped by a public method that:
//   1. Checks `libraryAvailable`.
//   2. Catches `UnsatisfiedLinkError` — thrown when an `external fun`
//      has no matching native implementation. Happens today because
//      engine/src/main/cpp/jni/DspJni.cpp has not been authored yet.
//   3. Catches `Exception` — any other native failure.
//
// Validation is split between "caller error" and "value out of range":
//
//   • Wrong array sizes / null where non-null is required → throws
//     `IllegalArgumentException`. These are programmer errors, not
//     runtime failures — silently degrading would hide bugs.
//   • Values out of documented range → clamped silently before the JNI
//     call. The caller passed a valid shape, just an edge value; there
//     is nothing wrong to log.
//
// ── Threading contract ─────────────────────────────────────────────────
// All public methods are synchronous. The DSP chain is shared with the
// audio render thread, so any setter causes the audio thread to see
// updated parameters at the next frame boundary (typically <10 ms).
//
//   • Setters do not block — they post the update and return.
//   • applyPreset / savePreset / listPresets touch disk (DspPresetStore)
//     and may block for 5–50 ms on first access. Subsequent calls are
//     served from an in-process cache.
//
// Callers using the fast-path setters during user interaction (slider
// drag, EQ band change) should send them on a background thread to avoid
// jank in the UI even though each individual call is sub-millisecond.
//
// ── JNI naming contract (for DspJni.cpp) ────────────────────────────────
// This object is `object DspBridge` in package `com.futura.engine`. The
// native functions must be named:
//
//     Java_com_futura_engine_DspBridge_nativeApplyConfig(...)
//     Java_com_futura_engine_DspBridge_nativeGetConfig(...)
//     ...
//
// `thiz` is a `jobject` (singleton instance), NOT a `jclass` — see
// NativeEngine.kt's JNI naming contract for the reasoning.
//
// ── Forward-reference audit ──────────────────────────────────────────
// Imported symbols:
//   • android.util.Log       (framework, all API levels)
//   • kotlin.jvm.Volatile    (Kotlin stdlib annotation)
//
// No project class, no Compose, no Hilt, no coroutines, no
// DspChainController (not delivered), no AudioTap, no data classes from
// :app. The five DspChainConfig-family classes below are file-local
// value objects authored here — they have no forward references and
// are exposed to callers through this file's public API.
//
// Compiles and functions standalone against the entire delivered
// codebase. Zero crashes even before DspJni.cpp exists (libraryAvailable
// = false → every wrapper returns its documented default).

package com.futura.engine

import android.util.Log

// ═════════════════════════════════════════════════════════════════════════
// Configuration value objects
// ═════════════════════════════════════════════════════════════════════════
//
// Split into a top-level DspChainConfig plus four logical sub-configs.
// Splitting (rather than one 21-field data class) keeps the API
// readable, lets callers update one domain without reconstructing the
// whole chain, and makes the JNI encoding table (see file header)
// organised by domain.
//
// All sub-configs are data classes with default values matching the DSP
// chain's neutral settings — a chain constructed from defaults is
// audibly identical to no DSP at all (except for the safety processors:
// DC blocking and loudness maximizer, which are always-on).

/**
 * Enhancement section: bass, stereo imaging, and binaural transformation.
 *
 * @param bassBoost Bass boost strength, `0f` (off) .. `1f` (max).
 *        Bass is generated via harmonic synthesis (feature #86), not
 *        just low-shelf EQ, so `1f` does not produce the distorted
 *        low-end typical of naïve bass-boost implementations.
 * @param stereoWidth Mid-Side width multiplier. `1f` is unity (no
 *        change), `0f` collapses to mono, `2f` doubles the side
 *        content. Values outside `[0f, 2f]` are clamped.
 * @param crossfeedEnabled Enable Bauer BS2B crossfeed (feature #83).
 *        Reduces listening fatigue on headphones by blending opposite
 *        channels with a small delay.
 * @param crossfeedAmount Crossfeed strength, `0f` (none) .. `1f` (max).
 *        Only relevant when `crossfeedEnabled = true`.
 * @param binauralEnabled Enable stereo-to-binaural conversion (feature
 *        #190). Uses HRTF convolution to simulate speaker sound from
 *        headphones. Mutually exclusive with crossfeed in practice;
 *        enabling one should disable the other at the caller level,
 *        not at the DSP chain level.
 */
data class EnhancementConfig(
    val bassBoost: Float = 0f,
    val stereoWidth: Float = 1f,
    val crossfeedEnabled: Boolean = false,
    val crossfeedAmount: Float = 0.3f,
    val binauralEnabled: Boolean = false
)

/**
 * Reverb section (feature #87 — Freeverb).
 *
 * @param enabled Whether reverb is active. Off by default — reverb
 *        during normal music playback colours the source, which most
 *        users do not want. Typically enabled for ambient / sleep
 *        sessions, or in the DJ Studio for transitions.
 * @param roomSize Room size, `0f` (small room) .. `1f` (large hall).
 * @param damping High-frequency damping, `0f` (bright) .. `1f` (dark).
 * @param wetLevel Wet signal level, `0f` (dry) .. `1f` (fully wet).
 */
data class ReverbConfig(
    val enabled: Boolean = false,
    val roomSize: Float = 0.5f,
    val damping: Float = 0.5f,
    val wetLevel: Float = 0.3f
)

/**
 * Dynamics section: compression, AGC, and loudness maximization.
 *
 * @param compressorEnabled Enable dynamic range compression (feature
 *        #84).
 * @param thresholdDb Compressor threshold in dBFS. Signals above this
 *        level are compressed. Typical values: `-18f` to `-12f`.
 * @param ratio Compression ratio. `4f` means 4:1 (input above
 *        threshold produces 1/4 as much output excess). `1f` = no
 *        compression (same as disabled).
 * @param attackMs Compressor attack time in milliseconds. `10f` is a
 *        reasonable default; lower values clip transients more
 *        aggressively but can sound "pumping".
 * @param releaseMs Compressor release time in milliseconds. `100f` is
 *        a reasonable default; higher values preserve loud passages,
 *        lower values pump more.
 * @param autoGainControlEnabled Enable automatic gain control (feature
 *        #188). AGC adjusts preamp gain dynamically based on the
 *        incoming signal, so tracks with very different mastering
 *        loudness play back at similar perceived levels.
 * @param loudnessMaximizerEnabled Enable the maximizer (feature #88).
 *        Raises quiet passages toward 0 dBFS. **Enabled by default**
 *        because this is a safety processor: it prevents user-volume
 *        sliders from accidentally clipping the output. Most users
 *        should never disable this.
 */
data class DynamicsConfig(
    val compressorEnabled: Boolean = false,
    val thresholdDb: Float = -18f,
    val ratio: Float = 4f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 100f,
    val autoGainControlEnabled: Boolean = false,
    val loudnessMaximizerEnabled: Boolean = true
)

/**
 * Channel configuration: phase and (future) per-channel gain.
 *
 * @param phaseInvertedLeft Invert the left channel's phase (feature
 *        #91). Useful for diagnosing phase-cancellation issues with
 *        mislabelled speakers or cabling.
 * @param phaseInvertedRight Invert the right channel's phase.
 */
data class ChannelConfig(
    val phaseInvertedLeft: Boolean = false,
    val phaseInvertedRight: Boolean = false
)

/**
 * Full DSP chain configuration.
 *
 * Immutable. Modify via `copy()` — every field has a documented default
 * so callers can construct only what they care about:
 *
 *     DspChainConfig(enhancement = EnhancementConfig(bassBoost = 0.6f))
 *
 * represents "default chain + 60% bass boost". Everything else stays at
 * defaults. This is the intended pattern for incremental updates.
 *
 * @param enabled Master enable. When false, the DSP chain is bypassed
 *        entirely — bit-perfect passthrough. When true, every enabled
 *        sub-processor runs.
 * @param preampDb Global preamp gain in dB, applied before the EQ. Used
 *        to compensate for the volume increase produced by EQ boosts.
 *        Range `[-20f, +20f]`; values outside are clamped.
 * @param eqBandGainsDb Per-band gain for the 10-band graphic EQ, in dB.
 *        **Must contain exactly 10 entries.** Order matches the standard
 *        graphic EQ frequency set:
 *        31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz.
 *        Each band's range is `[-12f, +12f]`; values outside are
 *        clamped per-band.
 * @param enhancement Bass, imaging, and binaural settings.
 * @param reverb Reverb settings.
 * @param dynamics Compressor / AGC / maximizer settings.
 * @param channel Phase inversion settings.
 */
data class DspChainConfig(
    val enabled: Boolean = true,
    val preampDb: Float = 0f,
    val eqBandGainsDb: List<Float> = List(EQ_BAND_COUNT) { 0f },
    val enhancement: EnhancementConfig = EnhancementConfig(),
    val reverb: ReverbConfig = ReverbConfig(),
    val dynamics: DynamicsConfig = DynamicsConfig(),
    val channel: ChannelConfig = ChannelConfig()
) {
    init {
        require(eqBandGainsDb.size == EQ_BAND_COUNT) {
            "eqBandGainsDb must contain exactly $EQ_BAND_COUNT entries; " +
                "got ${eqBandGainsDb.size}"
        }
    }
}

/**
 * Number of bands in the graphic EQ. Declared as a top-level const so
 * both the config validation and the array-encoding/decoding can
 * reference it.
 */
private const val EQ_BAND_COUNT = 10

// ═════════════════════════════════════════════════════════════════════════
// Bridge
// ═════════════════════════════════════════════════════════════════════════

/**
 * The 23-method JNI bridge to the native DSP chain.
 *
 * See the file header for the design contract, coverage table, bulk
 * config layout, threading, and JNI naming.
 */
object DspBridge {

    private const val TAG = "DspBridge"
    private const val LIBRARY_NAME = "futura_engine"

    /**
     * Total field count in the encoded-config layout. See file header's
     * "Bulk-config encoding" section.
     */
    private const val CONFIG_FIELD_COUNT = 30

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
                "Native library '$LIBRARY_NAME' unavailable — DSP disabled. " +
                    "Build :engine or check ABI filters if this is unexpected.",
                e
            )
            false
        }
    }

    /**
     * True if `libfutura_engine.so` loaded into the process. False means
     * the DSP chain is unavailable — audio plays through unprocessed
     * (bit-perfect passthrough is the correct fallback; the caller
     * should not attempt to apply DSP settings when this returns false).
     */
    fun isAvailable(): Boolean = libraryAvailable

    // ═════════════════════════════════════════════════════════════════
    // 1. Master toggle (2)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enable or disable the whole DSP chain.
     *
     * When disabled, audio bypasses every processor (including the
     * always-on safety processors) and is delivered bit-perfect to the
     * output. Use this for A/B comparison against the source, or as the
     * fallback when `NativePlayerBridge.setBitPerfectEnabled(true)`
     * succeeds and the user has explicitly chosen bit-perfect mode.
     *
     * @return true if the setting was applied, false if unavailable.
     */
    fun setDspEnabled(enabled: Boolean): Boolean =
        safeCall(false) { nativeSetDspEnabled(enabled) }

    /**
     * True if the DSP chain is currently active. Independent of
     * individual sub-processor enable flags — a chain with `enabled =
     * true` but every sub-processor off still returns true (the
     * always-on safety processors are running).
     */
    fun isDspEnabled(): Boolean = safeCall(false) { nativeIsDspEnabled() }

    // ═════════════════════════════════════════════════════════════════
    // 2. Bulk configuration (2)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Atomically apply a complete DSP chain configuration.
     *
     * All parameters are applied within a single native call, so
     * intermediate states are never rendered to the audio output. Use
     * this for preset loads and other multi-field updates. For
     * single-parameter updates during user interaction, use the
     * fast-path individual setters instead (they avoid the 30-float
     * encode/decode round trip).
     *
     * @return true on success, false if unavailable or validation fails
     *         at the native side (the input is validated by
     *         `DspChainConfig`'s `init` block before this call, so
     *         native-side validation is defence in depth only).
     */
    fun applyConfig(config: DspChainConfig): Boolean {
        val encoded = encodeConfig(config)
        return safeCall(false) { nativeApplyConfig(encoded) }
    }

    /**
     * Read the current DSP chain configuration.
     *
     * @return The full config, or null if unavailable or the native
     *         return had an unexpected shape (logged).
     */
    fun getConfig(): DspChainConfig? {
        val raw = safeCall<FloatArray?>(null) { nativeGetConfig() } ?: return null
        return decodeConfig(raw)
    }

    // ═════════════════════════════════════════════════════════════════
    // 3. Preamp (2)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Set the global preamp gain in dB. Clamps to `[-20f, +20f]`.
     */
    fun setPreampGain(db: Float) {
        safeCallUnit { nativeSetPreampGain(db.coerceIn(-20f, 20f)) }
    }

    /**
     * Current global preamp gain in dB.
     */
    fun getPreampGain(): Float = safeCall(0f) { nativeGetPreampGain() }

    // ═════════════════════════════════════════════════════════════════
    // 4. EQ — per-band and presets (5)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Set the gain for a single EQ band. This is the fast path used
     * during interactive slider drags — one JNI call, no data class
     * allocation.
     *
     * @param bandIndex `0..9`, matching the 10-band layout described on
     *        `DspChainConfig.eqBandGainsDb`. Values outside this range
     *        are rejected (returns without calling native).
     * @param gainDb Gain in dB. Clamps to `[-12f, +12f]`.
     */
    fun setEqBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until EQ_BAND_COUNT) {
            Log.w(TAG, "setEqBandGain: band index $bandIndex out of [0, ${EQ_BAND_COUNT})")
            return
        }
        safeCallUnit { nativeSetEqBandGain(bandIndex, gainDb.coerceIn(-12f, 12f)) }
    }

    /**
     * Current gain for a single EQ band.
     *
     * @param bandIndex `0..9`. Out-of-range indices return `0f`.
     */
    fun getEqBandGain(bandIndex: Int): Float {
        if (bandIndex !in 0 until EQ_BAND_COUNT) return 0f
        return safeCall(0f) { nativeGetEqBandGain(bandIndex) }
    }

    /**
     * Apply a stored preset by name. The preset's settings are applied
     * atomically (equivalent to `applyConfig(presetConfig)`).
     *
     * @return true if the preset exists and was applied, false
     *         otherwise (preset not found, I/O error on the preset
     *         store, or DSP unavailable).
     */
    fun applyPreset(presetName: String): Boolean =
        safeCall(false) { nativeApplyPreset(presetName) }

    /**
     * Save the current DSP chain configuration as a named preset.
     *
     * If a preset with the same name already exists, it is overwritten.
     * Preset storage is managed by `DspPresetStore` (feature #95) and
     * persists across process restarts.
     *
     * @return true on success, false on failure (I/O error, storage
     *         full, DSP unavailable).
     */
    fun savePreset(presetName: String): Boolean =
        safeCall(false) { nativeSavePreset(presetName) }

    /**
     * Names of every preset currently stored in the preset store.
     *
     * Order is unspecified — callers who display the list should sort
     * alphabetically for consistency.
     *
     * @return Preset names, or an empty array if the store is
     *         unavailable.
     */
    fun listPresets(): Array<String> =
        safeCall(emptyArray()) { nativeListPresets() }

    // ═════════════════════════════════════════════════════════════════
    // 5. Individual enhancement setters (5)
    // ═════════════════════════════════════════════════════════════════
    //
    // These are the fast-path setters for interactive tuning. They set
    // ONE parameter without touching any other field of the config,
    // unlike `applyConfig` which replaces the whole chain state.

    /**
     * Set bass boost strength. Clamps to `[0f, 1f]`.
     */
    fun setBassBoost(strength: Float) {
        safeCallUnit { nativeSetBassBoost(strength.coerceIn(0f, 1f)) }
    }

    /**
     * Set stereo width multiplier. Clamps to `[0f, 2f]`.
     */
    fun setStereoWidth(width: Float) {
        safeCallUnit { nativeSetStereoWidth(width.coerceIn(0f, 2f)) }
    }

    /**
     * Enable or disable Bauer BS2B crossfeed.
     */
    fun setCrossfeedEnabled(enabled: Boolean) {
        safeCallUnit { nativeSetCrossfeedEnabled(enabled) }
    }

    /**
     * Enable or disable stereo-to-binaural HRTF conversion.
     *
     * Enabling this while crossfeed is enabled produces
     * doubly-processed stereo — the DSP chain does not prevent this,
     * but callers who toggle one should toggle the other off in the
     * same UI action.
     */
    fun setBinauralEnabled(enabled: Boolean) {
        safeCallUnit { nativeSetBinauralEnabled(enabled) }
    }

    /**
     * Enable or disable the Freeverb reverb.
     */
    fun setReverbEnabled(enabled: Boolean) {
        safeCallUnit { nativeSetReverbEnabled(enabled) }
    }

    // ═════════════════════════════════════════════════════════════════
    // 6. Reverb parameters (1)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Update the three reverb tuning parameters. All values clamp to
     * `[0f, 1f]`.
     *
     * Bundled into a single method (rather than three individual
     * setters) because changing one without the others produces audible
     * artefacts during the transition — updating all three atomically
     * is the correct behaviour when a user drags one reverb slider.
     *
     * @param roomSize Room size `[0f, 1f]`.
     * @param damping High-frequency damping `[0f, 1f]`.
     * @param wetLevel Wet signal level `[0f, 1f]`.
     */
    fun setReverbParams(roomSize: Float, damping: Float, wetLevel: Float) {
        safeCallUnit {
            nativeSetReverbParams(
                roomSize.coerceIn(0f, 1f),
                damping.coerceIn(0f, 1f),
                wetLevel.coerceIn(0f, 1f)
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 7. Compressor (2)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enable or disable the dynamic range compressor.
     */
    fun setCompressorEnabled(enabled: Boolean) {
        safeCallUnit { nativeSetCompressorEnabled(enabled) }
    }

    /**
     * Update the four compressor parameters atomically. Clamping:
     *   • threshold `[-60f, 0f]` dBFS
     *   • ratio     `[1f, 20f]`
     *   • attack    `[0.1f, 200f]` ms
     *   • release   `[10f, 1000f]` ms
     */
    fun setCompressorParams(
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float
    ) {
        safeCallUnit {
            nativeSetCompressorParams(
                thresholdDb.coerceIn(-60f, 0f),
                ratio.coerceIn(1f, 20f),
                attackMs.coerceIn(0.1f, 200f),
                releaseMs.coerceIn(10f, 1000f)
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 8. Analysis — spectrum (1)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Read the current FFT spectrum for visualizers.
     *
     * The returned array has `FFT_SIZE / 2` bins (typically 512 bins
     * for a 1024-point FFT), with the smoothed amplitudes already
     * applied by `SpectrumSmoother` (feature #82). Values are in
     * `[0f, 1f]`, with 0 representing an empty bin and 1 representing
     * full-scale energy in that bin.
     *
     * Non-blocking: returns a snapshot at the moment of the call. The
     * visualizer caller polls this at 30–60 Hz.
     *
     * @return Spectrum bins, or null if unavailable.
     */
    fun getSpectrumData(): FloatArray? =
        safeCall<FloatArray?>(null) { nativeGetSpectrumData() }

    // ═════════════════════════════════════════════════════════════════
    // 9. Analysis — ReplayGain (1)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Run ReplayGain 2.0 analysis (feature #79) and return LUFS.
     *
     * This is a one-shot analysis method — it processes the current
     * source's audio and returns the integrated loudness. Typical call
     * pattern: the media indexer calls this once per track during
     * scanning, stores the LUFS value in `TrackEntity`, and the
     * player uses the stored value for volume normalisation.
     *
     * Blocking: takes 50–500 ms depending on track length and device.
     * **Call from a background thread.**
     *
     * @return Integrated loudness in LUFS (negative — typical music is
     *         -14 to -10 LUFS), or null if no source is loaded or
     *         analysis failed.
     */
    fun analyzeReplayGain(): Float? =
        safeCall<Float?>(null) { nativeAnalyzeReplayGain() }

    // ═════════════════════════════════════════════════════════════════
    // 10. Metering (2)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Current per-channel peak levels (feature #90).
     *
     * Returns a 2-element array `[leftPeak, rightPeak]` for stereo
     * sources, or one element per channel for mono / multichannel
     * sources. Values are linear amplitude in `[0f, 1f]`.
     *
     * Polling at 20–30 Hz is typical for a VU meter. Each call is
     * cheap (native side reads two floats), so the caller's update
     * frequency should be driven by UI needs, not cost.
     *
     * @return Per-channel peak levels, or null if unavailable.
     */
    fun getPeakLevels(): FloatArray? =
        safeCall<FloatArray?>(null) { nativeGetPeakLevels() }

    /**
     * True-peak level in dBFS across all channels (feature #189).
     *
     * True-peak differs from sample peak (which `getPeakLevels`
     * reports) by accounting for inter-sample peaks — the actual
     * peak that would result from a reconstruction filter. This is
     * the value that matters for lossy encoding and DAC output.
     *
     * Values in `[-inf, 0]` dBFS. Returns `Float.NEGATIVE_INFINITY`
     * for a silent signal; callers displaying this value should
     * handle that case (`if (db == -Infinity) "—" else "$db dBFS"`).
     *
     * @return True peak in dBFS, or `Float.NaN` if unavailable.
     */
    fun getTruePeakDb(): Float =
        safeCall(Float.NaN) { nativeGetTruePeakDb() }

    // ═════════════════════════════════════════════════════════════════
    // Safe-call helpers
    // ═════════════════════════════════════════════════════════════════

    private fun <T> safeCall(default: T, block: () -> T): T {
        if (!libraryAvailable) return default
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — DspJni.cpp likely not compiled", e)
            default
        } catch (e: Exception) {
            Log.e(TAG, "Native DSP call failed", e)
            default
        }
    }

    private fun safeCallUnit(block: () -> Unit) {
        if (!libraryAvailable) return
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — DspJni.cpp likely not compiled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Native DSP call failed", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Config encoding / decoding
    // ═════════════════════════════════════════════════════════════════

    /**
     * Encode [DspChainConfig] into the 30-float layout documented in the
     * file header. The caller has already validated `eqBandGainsDb.size`
     * via `DspChainConfig.init`, so no re-check is needed here.
     */
    private fun encodeConfig(config: DspChainConfig): FloatArray =
        FloatArray(CONFIG_FIELD_COUNT) { idx ->
            when (idx) {
                0 -> if (config.enabled) 1f else 0f
                1 -> config.preampDb
                in 2..11 -> config.eqBandGainsDb[idx - 2]
                12 -> config.enhancement.bassBoost
                13 -> config.enhancement.stereoWidth
                14 -> if (config.enhancement.crossfeedEnabled) 1f else 0f
                15 -> config.enhancement.crossfeedAmount
                16 -> if (config.enhancement.binauralEnabled) 1f else 0f
                17 -> if (config.reverb.enabled) 1f else 0f
                18 -> config.reverb.roomSize
                19 -> config.reverb.damping
                20 -> config.reverb.wetLevel
                21 -> if (config.dynamics.compressorEnabled) 1f else 0f
                22 -> config.dynamics.thresholdDb
                23 -> config.dynamics.ratio
                24 -> config.dynamics.attackMs
                25 -> config.dynamics.releaseMs
                26 -> if (config.dynamics.autoGainControlEnabled) 1f else 0f
                27 -> if (config.dynamics.loudnessMaximizerEnabled) 1f else 0f
                28 -> if (config.channel.phaseInvertedLeft) 1f else 0f
                29 -> if (config.channel.phaseInvertedRight) 1f else 0f
                else -> 0f
            }
        }

    /**
     * Decode the 30-float layout back into a [DspChainConfig]. Returns
     * null if the array is the wrong size — which would indicate a
     * mismatch between this file and JniBridge.cpp's return layout.
     */
    private fun decodeConfig(raw: FloatArray): DspChainConfig? {
        if (raw.size != CONFIG_FIELD_COUNT) {
            Log.w(
                TAG,
                "nativeGetConfig returned ${raw.size} floats, expected $CONFIG_FIELD_COUNT"
            )
            return null
        }
        return try {
            DspChainConfig(
                enabled = raw[0] != 0f,
                preampDb = raw[1],
                eqBandGainsDb = List(EQ_BAND_COUNT) { i -> raw[2 + i] },
                enhancement = EnhancementConfig(
                    bassBoost = raw[12],
                    stereoWidth = raw[13],
                    crossfeedEnabled = raw[14] != 0f,
                    crossfeedAmount = raw[15],
                    binauralEnabled = raw[16] != 0f
                ),
                reverb = ReverbConfig(
                    enabled = raw[17] != 0f,
                    roomSize = raw[18],
                    damping = raw[19],
                    wetLevel = raw[20]
                ),
                dynamics = DynamicsConfig(
                    compressorEnabled = raw[21] != 0f,
                    thresholdDb = raw[22],
                    ratio = raw[23],
                    attackMs = raw[24],
                    releaseMs = raw[25],
                    autoGainControlEnabled = raw[26] != 0f,
                    loudnessMaximizerEnabled = raw[27] != 0f
                ),
                channel = ChannelConfig(
                    phaseInvertedLeft = raw[28] != 0f,
                    phaseInvertedRight = raw[29] != 0f
                )
            )
        } catch (e: IllegalArgumentException) {
            // DspChainConfig's init requires eqBandGainsDb.size == 10,
            // which we guarantee above. This catch is defensive against
            // a future addition to init's validation.
            Log.e(TAG, "Decoded config failed validation", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Native declarations — 23 total
    // ═════════════════════════════════════════════════════════════════
    //
    // Private — callers cannot bypass the safe wrappers. JNI ignores
    // Kotlin visibility. `thiz` on the native side is a `jobject`
    // (singleton instance), NOT a `jclass`.

    // ── 1. Master toggle (2) ─────────────────────────────────────────
    private external fun nativeSetDspEnabled(enabled: Boolean): Boolean
    private external fun nativeIsDspEnabled(): Boolean

    // ── 2. Bulk configuration (2) ────────────────────────────────────
    private external fun nativeApplyConfig(configEncoded: FloatArray): Boolean
    private external fun nativeGetConfig(): FloatArray?

    // ── 3. Preamp (2) ────────────────────────────────────────────────
    private external fun nativeSetPreampGain(db: Float)
    private external fun nativeGetPreampGain(): Float

    // ── 4. EQ (5) ────────────────────────────────────────────────────
    private external fun nativeSetEqBandGain(bandIndex: Int, gainDb: Float)
    private external fun nativeGetEqBandGain(bandIndex: Int): Float
    private external fun nativeApplyPreset(presetName: String): Boolean
    private external fun nativeSavePreset(presetName: String): Boolean
    private external fun nativeListPresets(): Array<String>

    // ── 5. Enhancement individual setters (5) ────────────────────────
    private external fun nativeSetBassBoost(strength: Float)
    private external fun nativeSetStereoWidth(width: Float)
    private external fun nativeSetCrossfeedEnabled(enabled: Boolean)
    private external fun nativeSetBinauralEnabled(enabled: Boolean)
    private external fun nativeSetReverbEnabled(enabled: Boolean)

    // ── 6. Reverb parameters (1) ─────────────────────────────────────
    private external fun nativeSetReverbParams(
        roomSize: Float,
        damping: Float,
        wetLevel: Float
    )

    // ── 7. Compressor (2) ────────────────────────────────────────────
    private external fun nativeSetCompressorEnabled(enabled: Boolean)
    private external fun nativeSetCompressorParams(
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float
    )

    // ── 8. Analysis (2) ──────────────────────────────────────────────
    private external fun nativeGetSpectrumData(): FloatArray?
    private external fun nativeAnalyzeReplayGain(): Float?

    // ── 9. Metering (2) ──────────────────────────────────────────────
    private external fun nativeGetPeakLevels(): FloatArray?
    private external fun nativeGetTruePeakDb(): Float
}