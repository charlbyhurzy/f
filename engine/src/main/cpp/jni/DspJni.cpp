// futura-audio/engine/src/main/cpp/jni/DspJni.cpp
//
// Phase 4 — JNI implementation of DspBridge.kt (23 native methods).
//
// ── Path note ───────────────────────────────────────────────────────────
// The project tree (futura-audio.txt) lists this at
//     engine/src/main/cpp/jni/DspJni.cpp
// annotated "✅ 23 DSP methods". No path discrepancy — this is the
// canonical location. Kotlin counterpart:
//     engine/src/main/java/com/futura/engine/DspBridge.kt
//     (package com.futura.engine, object DspBridge)
//
// ── JNI naming contract ─────────────────────────────────────────────────
// Kotlin `object DspBridge` in package `com.futura.engine` mangles to:
//
//     Java_com_futura_engine_DspBridge_<methodName>
//
// The `thiz` parameter is a `jobject` (the singleton instance), NOT a
// `jclass` — because Kotlin object methods are instance methods.
// Confirmed in DspBridge.kt's KDoc; also documented in MlBridge.kt and
// NativeEngine.kt for consistency.
//
// ── Scope note (same pattern as Engine.cpp) ────────────────────────────
// None of the 26 DSP C++ headers under engine/src/main/cpp/dsp/ have
// been delivered yet. This file therefore implements the full
// *configuration state* surface today — every setter works end-to-end,
// every getter reflects what was set, and the interchange layout
// (configEncoded: FloatArray of 30 floats) matches DspBridge.kt's
// documented layout byte-for-byte. All setters are honest: they store
// into atomics that the future render loop will read.
//
// What this file honestly provides today:
//
//   • nativeSetDspEnabled / IsDspEnabled        — real (atomic bool)
//   • nativeApplyConfig / GetConfig             — real (30-float round-trip)
//   • nativeSetPreampGain / GetPreampGain       — real
//   • nativeSetEqBandGain / GetEqBandGain       — real (10-band array)
//   • nativeSetBassBoost / StereoWidth          — real
//   • nativeSetCrossfeed / Binaural / ReverbEnabled  — real
//   • nativeSetReverbParams                     — real
//   • nativeSetCompressorEnabled / Params       — real
//
// What this file does NOT provide today (all have a "GROWTH:" seam
// naming the exact header to wire in):
//
//   • nativeApplyPreset / SavePreset            — return false (DspPresetStore.h)
//   • nativeListPresets                         — returns empty array (DspPresetStore.h)
//   • nativeGetSpectrumData                     — returns nullptr (FFTAnalyzer.h + SpectrumSmoother.h)
//   • nativeAnalyzeReplayGain                   — returns nullptr (ReplayGainAnalyzer.h)
//   • nativeGetPeakLevels                       — returns nullptr (PeakLevelDetector.h)
//   • nativeGetTruePeakDb                       — returns NaN (TruePeakMeteringProcessor.h)
//
// Every one of those six is expected by DspBridge.kt to be able to
// return its "unavailable" sentinel — the Kotlin side handles nullptr /
// NaN / empty array gracefully (see DspBridge.kt's safeCall wrappers).
//
// ── 30-float interchange layout (must match DspBridge.kt) ──────────────
// Index | Field
// ------|-----------------------------------
// 0     | enabled                     (0/1)
// 1     | preampDb
// 2..11 | eqBandGainsDb[0..9]
// 12    | bassBoost
// 13    | stereoWidth
// 14    | crossfeedEnabled            (0/1)
// 15    | crossfeedAmount
// 16    | binauralEnabled             (0/1)
// 17    | reverbEnabled               (0/1)
// 18    | reverbRoomSize
// 19    | reverbDamping
// 20    | reverbWetLevel
// 21    | compressorEnabled           (0/1)
// 22    | compressorThresholdDb
// 23    | compressorRatio
// 24    | compressorAttackMs
// 25    | compressorReleaseMs
// 26    | autoGainControlEnabled      (0/1)
// 27    | loudnessMaximizerEnabled    (0/1)
// 28    | phaseInvertedLeft           (0/1)
// 29    | phaseInvertedRight          (0/1)
//
// Both sides MUST agree. If DspBridge.kt's layout changes, this file
// must change in the same commit.
//
// ── Threading ──────────────────────────────────────────────────────────
// Every method in this file can be called from:
//   • Control thread — the DspBridge.kt methods are called from UI or
//     MediaSessionCoordinator. Most are cheap atomic writes.
//   • Audio thread — never calls JNI directly; it reads the atomics
//     through C++ only. So the JNI layer's atomics are seen by the
//     audio thread without any JNI transition.
//
// This means every store/load here is on a control thread, but the
// values are read on the audio thread. atomics with memory_order_relaxed
// are sufficient — the DSP chain will read whatever value is current at
// the next render tick, and the exact ordering relative to other
// control-thread operations does not matter.
//
// ── Compile prerequisites ──────────────────────────────────────────────
// No project headers required. This file compiles standalone once the
// NDK is installed (via engine/build.gradle.kts's externalNativeBuild).
// The future DSP headers will be `#include`d here as growth.
//
// ── Forward-reference audit ────────────────────────────────────────────
// Included headers:
//   • <jni.h>            — NDK
//   • <android/log.h>    — NDK (for diagnostic logging on failure paths)
//   • <array>, <atomic>, <cmath>, <cstdint>, <limits> — stdlib
//
// Zero references to: GraphicEq10Band.h, ParametricFilterDesign.h,
// Biquad.h, ReplayGainAnalyzer.h, PreampGainController.h, FFTAnalyzer.h,
// SpectrumSmoother.h, CrossfeedProcessor.h, DynamicRangeCompressor.h,
// StereoWidthExpander.h, BassBoostSynthesizer.h, FreeverbEngine.h,
// LoudnessMaximizer.h, DitherGenerator.h, PeakLevelDetector.h,
// PhaseInverter.h, DcOffsetFilter.h, CrossfaderPipeline.h,
// AutomaticVolumeLimiter.h, DspPresetStore.h, AutoGainControlEngine.h,
// TruePeakMeteringProcessor.h, StereoToBinauralConverter.h,
// HighPassDcBlockingFilter.h, ReverbTailFlusher.h, SilencePaddingEngine.h,
// DspChain.h — all exist in tree, all future.

#include <jni.h>
#include <android/log.h>

#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <limits>

// ═════════════════════════════════════════════════════════════════════════
// File-local constants and state
// ═════════════════════════════════════════════════════════════════════════

namespace {

constexpr const char* LOG_TAG = "DspJni";

// Interchange layout constants — must match DspBridge.kt.
constexpr int CONFIG_FIELD_COUNT = 30;
constexpr int EQ_BAND_COUNT      = 10;

// Bounds mirror DspBridge.kt's documented clamping ranges. The Kotlin
// side already clamps before calling JNI, but defensive re-clamping
// protects against direct JNI callers (e.g., future tests or advanced
// users). Cost is negligible.
constexpr float PREAMP_DB_MIN      = -20.0f;
constexpr float PREAMP_DB_MAX      =  20.0f;
constexpr float EQ_BAND_MIN_DB     = -12.0f;
constexpr float EQ_BAND_MAX_DB     =  12.0f;
constexpr float BASS_BOOST_MIN     =   0.0f;
constexpr float BASS_BOOST_MAX     =   1.0f;
constexpr float STEREO_WIDTH_MIN   =   0.0f;
constexpr float STEREO_WIDTH_MAX   =   2.0f;
constexpr float UNIT_RANGE_MIN     =   0.0f;   // for reverb / crossfeed
constexpr float UNIT_RANGE_MAX     =   1.0f;
constexpr float COMP_THRESHOLD_MIN = -60.0f;
constexpr float COMP_THRESHOLD_MAX =   0.0f;
constexpr float COMP_RATIO_MIN     =   1.0f;
constexpr float COMP_RATIO_MAX     =  20.0f;
constexpr float COMP_ATTACK_MIN_MS =   0.1f;
constexpr float COMP_ATTACK_MAX_MS = 200.0f;
constexpr float COMP_RELEASE_MIN_MS =  10.0f;
constexpr float COMP_RELEASE_MAX_MS = 1000.0f;

// ── DSP state — mirrors DspChainConfig's defaults from DspBridge.kt ────
//
// The defaults exactly match DspChainConfig{}. All stores/loads from
// JNI use memory_order_relaxed — see file header's "Threading" section.
// The audio render loop (future) will read these atomics without any
// JNI transition.

struct DspState {

    // Master toggle — matches DspChainConfig.enabled = true
    std::atomic<bool> enabled{true};

    // Preamp — matches DspChainConfig.preampDb = 0f
    std::atomic<float> preamp_db{0.0f};

    // 10-band EQ — matches DspChainConfig.eqBandGainsDb = List(10){0f}
    // Initialized explicitly in constructor below.
    std::array<std::atomic<float>, EQ_BAND_COUNT> eq_bands;

    // Enhancement — matches EnhancementConfig defaults
    std::atomic<float> bass_boost{0.0f};
    std::atomic<float> stereo_width{1.0f};
    std::atomic<bool>  crossfeed_enabled{false};
    std::atomic<float> crossfeed_amount{0.3f};
    std::atomic<bool>  binaural_enabled{false};

    // Reverb — matches ReverbConfig defaults
    std::atomic<bool>  reverb_enabled{false};
    std::atomic<float> reverb_room_size{0.5f};
    std::atomic<float> reverb_damping{0.5f};
    std::atomic<float> reverb_wet_level{0.3f};

    // Dynamics — matches DynamicsConfig defaults
    std::atomic<bool>  compressor_enabled{false};
    std::atomic<float> compressor_threshold{-18.0f};
    std::atomic<float> compressor_ratio{4.0f};
    std::atomic<float> compressor_attack_ms{10.0f};
    std::atomic<float> compressor_release_ms{100.0f};
    std::atomic<bool>  agc_enabled{false};
    std::atomic<bool>  maximizer_enabled{true};

    // Channel — matches ChannelConfig defaults
    std::atomic<bool>  phase_inverted_left{false};
    std::atomic<bool>  phase_inverted_right{false};

    DspState() {
        // std::array<std::atomic<float>, N> requires explicit per-element
        // initialization before C++20 — std::atomic<float>'s default
        // constructor leaves the contained value uninitialized pre-C++20.
        for (auto& b : eq_bands) {
            b.store(0.0f, std::memory_order_relaxed);
        }
    }
};

// Single process-wide instance — Kotlin `object DspBridge` guarantees
// exactly one caller, so a file-local static is sufficient. This is
// the same pattern as NativeEngine.kt and MlBridge.kt's JNI bridges.
DspState g_state;

// ═════════════════════════════════════════════════════════════════════════
// Helpers — encode / decode the 30-float config
// ═════════════════════════════════════════════════════════════════════════

float clampf(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

// Decode the 30-float array from Kotlin into the atomic state.
// Layout MUST match DspBridge.kt's documented mapping (see file header).
void decode_config_to_state(const float* config, DspState& s) {
    s.enabled.store(config[0] != 0.0f, std::memory_order_relaxed);
    s.preamp_db.store(
        clampf(config[1], PREAMP_DB_MIN, PREAMP_DB_MAX),
        std::memory_order_relaxed);
    for (int i = 0; i < EQ_BAND_COUNT; ++i) {
        s.eq_bands[i].store(
            clampf(config[2 + i], EQ_BAND_MIN_DB, EQ_BAND_MAX_DB),
            std::memory_order_relaxed);
    }
    s.bass_boost.store(
        clampf(config[12], BASS_BOOST_MIN, BASS_BOOST_MAX),
        std::memory_order_relaxed);
    s.stereo_width.store(
        clampf(config[13], STEREO_WIDTH_MIN, STEREO_WIDTH_MAX),
        std::memory_order_relaxed);
    s.crossfeed_enabled.store(config[14] != 0.0f, std::memory_order_relaxed);
    s.crossfeed_amount.store(
        clampf(config[15], UNIT_RANGE_MIN, UNIT_RANGE_MAX),
        std::memory_order_relaxed);
    s.binaural_enabled.store(config[16] != 0.0f, std::memory_order_relaxed);
    s.reverb_enabled.store(config[17] != 0.0f, std::memory_order_relaxed);
    s.reverb_room_size.store(
        clampf(config[18], UNIT_RANGE_MIN, UNIT_RANGE_MAX),
        std::memory_order_relaxed);
    s.reverb_damping.store(
        clampf(config[19], UNIT_RANGE_MIN, UNIT_RANGE_MAX),
        std::memory_order_relaxed);
    s.reverb_wet_level.store(
        clampf(config[20], UNIT_RANGE_MIN, UNIT_RANGE_MAX),
        std::memory_order_relaxed);
    s.compressor_enabled.store(config[21] != 0.0f, std::memory_order_relaxed);
    s.compressor_threshold.store(
        clampf(config[22], COMP_THRESHOLD_MIN, COMP_THRESHOLD_MAX),
        std::memory_order_relaxed);
    s.compressor_ratio.store(
        clampf(config[23], COMP_RATIO_MIN, COMP_RATIO_MAX),
        std::memory_order_relaxed);
    s.compressor_attack_ms.store(
        clampf(config[24], COMP_ATTACK_MIN_MS, COMP_ATTACK_MAX_MS),
        std::memory_order_relaxed);
    s.compressor_release_ms.store(
        clampf(config[25], COMP_RELEASE_MIN_MS, COMP_RELEASE_MAX_MS),
        std::memory_order_relaxed);
    s.agc_enabled.store(config[26] != 0.0f, std::memory_order_relaxed);
    s.maximizer_enabled.store(config[27] != 0.0f, std::memory_order_relaxed);
    s.phase_inverted_left.store(config[28] != 0.0f, std::memory_order_relaxed);
    s.phase_inverted_right.store(config[29] != 0.0f, std::memory_order_relaxed);
}

// Encode the atomic state into a 30-float array. Reverse of decode.
void encode_state_to_config(const DspState& s, float* out) {
    out[0] = s.enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[1] = s.preamp_db.load(std::memory_order_relaxed);
    for (int i = 0; i < EQ_BAND_COUNT; ++i) {
        out[2 + i] = s.eq_bands[i].load(std::memory_order_relaxed);
    }
    out[12] = s.bass_boost.load(std::memory_order_relaxed);
    out[13] = s.stereo_width.load(std::memory_order_relaxed);
    out[14] = s.crossfeed_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[15] = s.crossfeed_amount.load(std::memory_order_relaxed);
    out[16] = s.binaural_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[17] = s.reverb_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[18] = s.reverb_room_size.load(std::memory_order_relaxed);
    out[19] = s.reverb_damping.load(std::memory_order_relaxed);
    out[20] = s.reverb_wet_level.load(std::memory_order_relaxed);
    out[21] = s.compressor_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[22] = s.compressor_threshold.load(std::memory_order_relaxed);
    out[23] = s.compressor_ratio.load(std::memory_order_relaxed);
    out[24] = s.compressor_attack_ms.load(std::memory_order_relaxed);
    out[25] = s.compressor_release_ms.load(std::memory_order_relaxed);
    out[26] = s.agc_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[27] = s.maximizer_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[28] = s.phase_inverted_left.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[29] = s.phase_inverted_right.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
}

}  // anonymous namespace

// ═════════════════════════════════════════════════════════════════════════
// JNI entry points — 23 total
// ═════════════════════════════════════════════════════════════════════════
//
// All functions use `jobject thiz` (Kotlin `object` = singleton instance).
// See file header's "JNI naming contract" section.

extern "C" {

// ── 1. Master toggle (2) ────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_DspBridge_nativeSetDspEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    g_state.enabled.store(enabled != JNI_FALSE, std::memory_order_relaxed);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_DspBridge_nativeIsDspEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_state.enabled.load(std::memory_order_relaxed) ? JNI_TRUE
                                                           : JNI_FALSE;
}

// ── 2. Bulk configuration (2) ───────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_DspBridge_nativeApplyConfig(
    JNIEnv* env, jobject /*thiz*/, jfloatArray configEncoded) {

    if (configEncoded == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeApplyConfig: null config array");
        return JNI_FALSE;
    }

    const jsize len = env->GetArrayLength(configEncoded);
    if (len != CONFIG_FIELD_COUNT) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeApplyConfig: expected %d floats, got %d",
            CONFIG_FIELD_COUNT, static_cast<int>(len));
        return JNI_FALSE;
    }

    jfloat local[CONFIG_FIELD_COUNT];
    env->GetFloatArrayRegion(configEncoded, 0, CONFIG_FIELD_COUNT, local);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeApplyConfig: GetFloatArrayRegion failed");
        return JNI_FALSE;
    }

    decode_config_to_state(local, g_state);
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_DspBridge_nativeGetConfig(
    JNIEnv* env, jobject /*thiz*/) {

    jfloat local[CONFIG_FIELD_COUNT];
    encode_state_to_config(g_state, local);

    jfloatArray result = env->NewFloatArray(CONFIG_FIELD_COUNT);
    if (result == nullptr) {
        // OOM — Kotlin side falls back to null.
        return nullptr;
    }

    env->SetFloatArrayRegion(result, 0, CONFIG_FIELD_COUNT, local);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

// ── 3. Preamp (2) ───────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetPreampGain(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat db) {
    g_state.preamp_db.store(
        clampf(db, PREAMP_DB_MIN, PREAMP_DB_MAX),
        std::memory_order_relaxed);
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_DspBridge_nativeGetPreampGain(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_state.preamp_db.load(std::memory_order_relaxed);
}

// ── 4. EQ (5) ───────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetEqBandGain(
    JNIEnv* env, jobject /*thiz*/, jint bandIndex, jfloat gainDb) {

    if (bandIndex < 0 || bandIndex >= EQ_BAND_COUNT) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeSetEqBandGain: band index %d out of [0, %d)",
            static_cast<int>(bandIndex), EQ_BAND_COUNT);
        return;
    }
    g_state.eq_bands[bandIndex].store(
        clampf(gainDb, EQ_BAND_MIN_DB, EQ_BAND_MAX_DB),
        std::memory_order_relaxed);
    (void)env;  // env unused after validation
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_DspBridge_nativeGetEqBandGain(
    JNIEnv* /*env*/, jobject /*thiz*/, jint bandIndex) {

    if (bandIndex < 0 || bandIndex >= EQ_BAND_COUNT) {
        return 0.0f;
    }
    return g_state.eq_bands[bandIndex].load(std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_DspBridge_nativeApplyPreset(
    JNIEnv* /*env*/, jobject /*thiz*/, jstring /*presetName*/) {
    // GROWTH: when DspPresetStore.h is delivered, read presetName via
    // GetStringUTFChars, look up the preset in the store, and call
    // decode_config_to_state() with its config.
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_DspBridge_nativeSavePreset(
    JNIEnv* /*env*/, jobject /*thiz*/, jstring /*presetName*/) {
    // GROWTH: when DspPresetStore.h is delivered, read presetName and
    // encode_state_to_config() → store as a named preset.
    return JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_futura_engine_DspBridge_nativeListPresets(
    JNIEnv* env, jobject /*thiz*/) {

    // Return an empty String[] — "bridge is available, no presets stored".
    // The Kotlin side's safeCall wraps this with emptyArray() default, so
    // a null return would also be handled, but an empty array is the more
    // honest semantic.
    //
    // GROWTH: when DspPresetStore.h is delivered, read the store's list
    // of names and return them.
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeListPresets: FindClass(String) failed");
        return nullptr;
    }
    return env->NewObjectArray(0, stringClass, nullptr);
}

// ── 5. Individual enhancement setters (5) ──────────────────────────────

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetBassBoost(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat strength) {
    g_state.bass_boost.store(
        clampf(strength, BASS_BOOST_MIN, BASS_BOOST_MAX),
        std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetStereoWidth(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat width) {
    g_state.stereo_width.store(
        clampf(width, STEREO_WIDTH_MIN, STEREO_WIDTH_MAX),
        std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetCrossfeedEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    g_state.crossfeed_enabled.store(enabled != JNI_FALSE,
                                    std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetBinauralEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    g_state.binaural_enabled.store(enabled != JNI_FALSE,
                                   std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetReverbEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    g_state.reverb_enabled.store(enabled != JNI_FALSE,
                                 std::memory_order_relaxed);
}

// ── 6. Reverb parameters (1) ───────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetReverbParams(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat roomSize, jfloat damping, jfloat wetLevel) {

    g_state.reverb_room_size.store(
        clampf(roomSize, UNIT_RANGE_MIN, UNIT_RANGE_MAX),
        std::memory_order_relaxed);
    g_state.reverb_damping.store(
        clampf(damping, UNIT_RANGE_MIN, UNIT_RANGE_MAX),
        std::memory_order_relaxed);
    g_state.reverb_wet_level.store(
        clampf(wetLevel, UNIT_RANGE_MIN, UNIT_RANGE_MAX),
        std::memory_order_relaxed);
}

// ── 7. Compressor (2) ──────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetCompressorEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    g_state.compressor_enabled.store(enabled != JNI_FALSE,
                                     std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_futura_engine_DspBridge_nativeSetCompressorParams(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat thresholdDb, jfloat ratio, jfloat attackMs, jfloat releaseMs) {

    g_state.compressor_threshold.store(
        clampf(thresholdDb, COMP_THRESHOLD_MIN, COMP_THRESHOLD_MAX),
        std::memory_order_relaxed);
    g_state.compressor_ratio.store(
        clampf(ratio, COMP_RATIO_MIN, COMP_RATIO_MAX),
        std::memory_order_relaxed);
    g_state.compressor_attack_ms.store(
        clampf(attackMs, COMP_ATTACK_MIN_MS, COMP_ATTACK_MAX_MS),
        std::memory_order_relaxed);
    g_state.compressor_release_ms.store(
        clampf(releaseMs, COMP_RELEASE_MIN_MS, COMP_RELEASE_MAX_MS),
        std::memory_order_relaxed);
}

// ── 8. Analysis (2) ────────────────────────────────────────────────────

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_DspBridge_nativeGetSpectrumData(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // GROWTH: when FFTAnalyzer.h + SpectrumSmoother.h are delivered,
    // read the smoothed spectrum bin array and return it as a jfloatArray.
    // Currently: no FFT analyzer exists, so no spectrum to report.
    // Kotlin's safeCall<FloatArray?>(null) handles null correctly.
    return nullptr;
}

JNIEXPORT jobject JNICALL
Java_com_futura_engine_DspBridge_nativeAnalyzeReplayGain(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // GROWTH: when ReplayGainAnalyzer.h is delivered, run analysis and
    // return a boxed java.lang.Float. Requires constructing a Float
    // object via env->CallStaticObjectMethod(Float.class, valueOf, jfloat).
    // Currently: analysis would require decoding the entire track, which
    // the JNI bridge cannot do without a decoder. Return null (Kotlin
    // receives Float? = null).
    return nullptr;
}

// ── 9. Metering (2) ────────────────────────────────────────────────────

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_DspBridge_nativeGetPeakLevels(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // GROWTH: when PeakLevelDetector.h is delivered, read the two peak
    // values (left, right) and return them as a jfloatArray of size 2.
    // Currently: no audio path exists to meter, so no peak levels.
    return nullptr;
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_DspBridge_nativeGetTruePeakDb(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // GROWTH: when TruePeakMeteringProcessor.h is delivered, return the
    // inter-sample peak in dBFS.
    // Currently: no audio path exists to meter.
    return std::numeric_limits<float>::quiet_NaN();
}

}  // extern "C"