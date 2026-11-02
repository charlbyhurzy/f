// futura-audio/engine/src/main/cpp/jni/MlJni.cpp
//
// JNI for MlBridge.kt — 20 methods.
// Growth seam: engine/src/main/cpp/ml/*.h (26 headers in tree, none delivered).

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>

namespace {

constexpr const char* LOG_TAG = "MlJni";

// Kotlin-side sentinels for unavailable (see MlBridge.kt defaults):
//   Float  → NaN
//   Int    → -1
//   arrays → nullptr
//   Boolean → JNI_FALSE
constexpr jint  SENTINEL_INT   = -1;
constexpr jfloat SENTINEL_FLOAT =
    std::numeric_limits<float>::quiet_NaN();

struct MlState {
    std::atomic<bool> initialized{false};
    // Written only from nativeInit (control thread). Guarded for future
    // re-init from another thread if that ever happens.
    std::mutex  models_dir_mutex;
    std::string models_dir;
    bool        use_gpu      = false;
    int         num_threads  = 2;
};

MlState g_state;

}  // anonymous namespace

extern "C" {

// ── 1. Lifecycle (2) ──────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_ml_MlBridge_nativeInit(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelsDir, jboolean useGpu, jint numThreads) {

    if (modelsDir == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeInit: null modelsDir");
        return JNI_FALSE;
    }

    const char* chars = env->GetStringUTFChars(modelsDir, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    std::string dir(chars);
    env->ReleaseStringUTFChars(modelsDir, chars);

    {
        std::lock_guard<std::mutex> lock(g_state.models_dir_mutex);
        g_state.models_dir  = std::move(dir);
        g_state.use_gpu     = (useGpu != JNI_FALSE);
        g_state.num_threads = static_cast<int>(numThreads);
    }

    // GROWTH: when ml/TfliteRuntime.h is delivered, this becomes:
    //   1. TfliteRuntime::initialize(g_state.models_dir, use_gpu, num_threads)
    //   2. Load all models (stems separator, tagger, key detector, …)
    //   3. Set g_state.initialized = true on success
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
        "nativeInit: ML backend not available (modelsDir=%s, gpu=%d, threads=%d)",
        g_state.models_dir.c_str(),
        static_cast<int>(g_state.use_gpu),
        g_state.num_threads);

    g_state.initialized.store(false, std::memory_order_relaxed);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_futura_engine_ml_MlBridge_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // GROWTH: TfliteRuntime::shutdown() + model unload.
    g_state.initialized.store(false, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_state.models_dir_mutex);
    g_state.models_dir.clear();
}

// ── 2. Audio analysis (8) ─────────────────────────────────────────────

JNIEXPORT jobjectArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeSeparateStems(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/StemsSeparator.h — 4 float arrays (vocals, drums, bass, other).
    return nullptr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeExtractMfcc(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/, jint /*nMfcc*/) {
    // GROWTH: ml/MfccExtractor.h.
    return nullptr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeGenerateEmbedding(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/EmbeddingGenerator.h.
    return nullptr;
}

JNIEXPORT jintArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeVectorSearch(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*query*/,
    jobjectArray /*database*/,
    jint /*topK*/) {
    // GROWTH: ml/LocalVectorDatabase.h.
    return nullptr;
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_ml_MlBridge_nativeAcousticSimilarity(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*a*/, jfloatArray /*b*/) {
    // GROWTH: ml/AcousticSimilarityEngine.h.
    return SENTINEL_FLOAT;
}

JNIEXPORT jobjectArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeAutoTag(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/AutoTaggingClassifier.h. Must return String[] of form
    // "G|label|score" / "M|label|score" / "I|label|score" — parsed by
    // MlBridge.kt's nativeAutoTag wrapper.
    return nullptr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeGenerateQueue(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jobjectArray /*seedEmbeddings*/,
    jobjectArray /*candidateEmbeddings*/,
    jobjectArray /*candidateIds*/,
    jint /*maxSize*/) {
    // GROWTH: ml/SmartQueueGenerator.h.
    return nullptr;
}

JNIEXPORT jint JNICALL
Java_com_futura_engine_ml_MlBridge_nativeDetectKey(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/KeyDetector.h. -1 = unavailable (MlBridge.kt default).
    return SENTINEL_INT;
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_ml_MlBridge_nativePredictLoudness(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/MlVolumeNormalizer.h.
    return SENTINEL_FLOAT;
}

// ── 3. Audio transformation (6) ───────────────────────────────────────

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeExtractVocals(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/VocalExtractor.h.
    return nullptr;
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_ml_MlBridge_nativeClassifySpeechMusic(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/SpeechMusicClassifier.h.
    return SENTINEL_FLOAT;
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_ml_MlBridge_nativeScoreRecommendation(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*userEmbedding*/, jfloatArray /*trackEmbedding*/) {
    // GROWTH: ml/RecommendationEngine.h.
    return SENTINEL_FLOAT;
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_ml_MlBridge_nativePredictSkip(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*contextEmbedding*/, jfloatArray /*trackEmbedding*/) {
    // GROWTH: ml/SkipPredictor.h.
    return SENTINEL_FLOAT;
}

JNIEXPORT jbyteArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeFingerprint(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/AudioFingerprinter.h.
    return nullptr;
}

JNIEXPORT jlongArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeAlignLyrics(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jstring /*plainText*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/LyricSyncMatcher.h — one start-ms per lyric line,
    // in input order. The Kotlin OfflineLyricSynchronizer's LineAligner
    // adapter feeds this. Until delivered, the Kotlin-side heuristic
    // (proportional-duration) is the fallback.
    return nullptr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeUpscale(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/AudioUpscaler.h.
    return nullptr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeDenoise(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/) {
    // GROWTH: ml/NoiseReductionEngine.h.
    return nullptr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_futura_engine_ml_MlBridge_nativeIsolateInstrument(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloatArray /*pcmData*/, jint /*sampleRate*/, jint /*instrumentId*/) {
    // GROWTH: ml/InstrumentSoloIsolator.h.
    return nullptr;
}

}  // extern "C"