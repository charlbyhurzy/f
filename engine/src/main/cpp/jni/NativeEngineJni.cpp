// futura-audio/engine/src/main/cpp/jni/NativeEngineJni.cpp
//
// JNI for NativeEngine.kt — 15 methods (crash handler lives in
// CrashHandlerJni.cpp).
//
// Prerequisite: AudioOutput.h and OboeStreamBuilder.h need the
// oboe:: → ::oboe:: fix before this file compiles (Engine.h transitively
// includes both).
//
// Threading: NativeEngine.kt calls these from any app thread, so every
// access to g_engine is serialized by g_engine_mutex. C++ exceptions must
// never cross the JNI boundary, so every call that can throw is wrapped.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <sched.h>
#include <unistd.h>

#include "core/Engine.h"

namespace {

constexpr const char* LOG_TAG          = "NativeEngineJni";
constexpr const char* ENGINE_VERSION   = "1.0.0";
constexpr jfloat      SENTINEL_FLOAT   = __builtin_nanf("");

// Guards g_engine. Every JNI entry that touches the engine takes this lock,
// so a concurrent nativeShutdown() cannot free the engine under a getter.
std::mutex g_engine_mutex;

// Single process-wide Engine instance. Constructed on nativeInitialize,
// destroyed on nativeShutdown. Matches the Kotlin `object NativeEngine`
// singleton semantics. Protected by g_engine_mutex.
std::unique_ptr<futura::engine::Engine> g_engine;

// Xrun counter — placeholder until Engine exposes xrun tracking.
// Enabling/disabling at initialize time only records the intent.
std::atomic<bool>    g_xrun_detection_enabled{false};
std::atomic<int64_t> g_xrun_count{0};

// Log level — stored but not yet applied (see nativeSetLogLevel).
std::atomic<int> g_log_level{1};  // 1 = WARN

#ifdef NDEBUG
constexpr const char* BUILD_TYPE = "release";
#else
constexpr const char* BUILD_TYPE = "debug";
#endif

// Read a numeric field from /proc/self/status, reported in kB. Returns -1
// on failure. Not async-signal-safe — only called from control-thread JNI
// entry points.
int64_t read_proc_status_kb(const char* field) {
    FILE* f = std::fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    size_t field_len = std::strlen(field);
    int64_t result = -1;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::strncmp(line, field, field_len) == 0 && line[field_len] == ':') {
            const char* p = line + field_len + 1;
            while (*p == ' ' || *p == '\t') ++p;
            long long v = std::strtoll(p, nullptr, 10);
            if (v >= 0) result = static_cast<int64_t>(v);
            break;
        }
    }
    std::fclose(f);
    return result;
}

}  // anonymous namespace

extern "C" {

// ── Lifecycle (2) ─────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativeEngine_nativeInitialize(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint preferredSampleRate, jint preferredFramesPerBurst,
    jint performanceMode, jboolean enableBitPerfect,
    jboolean enableXRunDetection) {

    std::lock_guard<std::mutex> lock(g_engine_mutex);

    try {
        if (g_engine && g_engine->isInitialized()) {
            g_engine->shutdown();
        }

        futura::engine::EngineConfig config;
        config.preferred_sample_rate      = static_cast<int32_t>(preferredSampleRate);
        config.preferred_frames_per_burst = static_cast<int32_t>(preferredFramesPerBurst);
        config.performance_mode           = static_cast<int32_t>(performanceMode);
        config.enable_bit_perfect         = (enableBitPerfect != JNI_FALSE);
        config.enable_xrun_detection      = (enableXRunDetection != JNI_FALSE);

        // Build the new engine locally so a failed initialize() never
        // leaves a half-constructed engine in g_engine.
        auto engine = std::make_unique<futura::engine::Engine>();
        if (!engine->initialize(config)) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                "nativeInitialize: Engine::initialize failed: %s",
                engine->getLastError().c_str());
            g_engine.reset();
            return JNI_FALSE;
        }

        g_engine = std::move(engine);
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeInitialize: exception: %s", e.what());
        g_engine.reset();
        return JNI_FALSE;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeInitialize: unknown exception");
        g_engine.reset();
        return JNI_FALSE;
    }

    g_xrun_detection_enabled.store(enableXRunDetection != JNI_FALSE,
                                   std::memory_order_relaxed);
    g_xrun_count.store(0, std::memory_order_relaxed);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_futura_engine_NativeEngine_nativeShutdown(
    JNIEnv* /*env*/, jobject /*thiz*/) {

    std::lock_guard<std::mutex> lock(g_engine_mutex);

    if (g_engine) {
        try {
            g_engine->shutdown();
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                "nativeShutdown: exception during shutdown");
        }
        g_engine.reset();
    }
    g_xrun_detection_enabled.store(false, std::memory_order_relaxed);
    g_xrun_count.store(0, std::memory_order_relaxed);
}

// ── Version / build info (2) ──────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_futura_engine_NativeEngine_nativeGetVersionString(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(ENGINE_VERSION);
}

JNIEXPORT jstring JNICALL
Java_com_futura_engine_NativeEngine_nativeGetBuildType(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(BUILD_TYPE);
}

// ── Memory profiling (3) ──────────────────────────────────────────────
//
// Resident and peak-resident come from /proc/self/status:
//   VmRSS — current resident set size
//   VmHWM — peak resident set size (high-water mark)
// VmPeak is the peak *virtual* size, which is not what "peak resident"
// means, so it is not used here.
// Native-heap is not separately trackable without per-allocation tracking
// (profiler/MemoryLeakProfiler.h, future) — returns -1 (Kotlin's "unknown").

JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativeEngine_nativeGetResidentMemoryBytes(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    int64_t kb = read_proc_status_kb("VmRSS");
    return kb >= 0 ? static_cast<jlong>(kb * 1024) : static_cast<jlong>(-1);
}

JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativeEngine_nativeGetPeakResidentMemoryBytes(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    int64_t kb = read_proc_status_kb("VmHWM");
    return kb >= 0 ? static_cast<jlong>(kb * 1024) : static_cast<jlong>(-1);
}

JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativeEngine_nativeGetNativeHeapAllocatedBytes(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // GROWTH: profiler/MemoryLeakProfiler.h will expose per-allocation
    // tracking. Until then, no reliable number exists — return -1.
    return static_cast<jlong>(-1);
}

// ── Device audio info (3) ─────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_futura_engine_NativeEngine_nativeGetDeviceSampleRate(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return g_engine ? static_cast<jint>(g_engine->getDeviceSampleRate()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_futura_engine_NativeEngine_nativeGetDeviceFramesPerBurst(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return g_engine ? static_cast<jint>(g_engine->getDeviceFramesPerBurst()) : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_futura_engine_NativeEngine_nativeGetDeviceHardwareLatencyMs(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return g_engine
        ? static_cast<jfloat>(g_engine->getDeviceHardwareLatencyMs())
        : SENTINEL_FLOAT;
}

// ── XRun detection (2) ────────────────────────────────────────────────
//
// GROWTH: Engine.h does not yet expose xrun tracking. When it does
// (likely as Engine::getXRunCount / resetXRunCount), these forward.
// Until then, counter is local to this file — always 0 because nothing
// increments it. Kotlin-side sees a valid but uninformative 0.

JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativeEngine_nativeGetXRunCount(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(g_xrun_count.load(std::memory_order_relaxed));
}

JNIEXPORT void JNICALL
Java_com_futura_engine_NativeEngine_nativeResetXRunCount(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    g_xrun_count.store(0, std::memory_order_relaxed);
}

// ── Audio thread priority (2) ─────────────────────────────────────────
//
// SCHED_FIFO per-thread; caller must invoke from the audio thread itself
// (see NativeEngine.kt KDoc). Priority 3 is the typical Android audio
// thread priority; explicit SCHED_FIFO attempts may fail without
// CAP_SYS_NICE on newer Android — returns false in that case.

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativeEngine_nativeEscalateAudioThreadPriority(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    struct sched_param param;
    std::memset(&param, 0, sizeof(param));
    param.sched_priority = 3;
    // pid 0 = calling thread.
    if (sched_setscheduler(0, SCHED_FIFO, &param) != 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "escalateAudioThreadPriority: sched_setscheduler failed: %d", errno);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_futura_engine_NativeEngine_nativeGetCurrentAudioThreadPriority(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // Returns the scheduling *policy* (SCHED_OTHER=0, SCHED_FIFO=1,
    // SCHED_RR=2), matching NativeEngine.kt's KDoc. -1 on error.
    return static_cast<jint>(sched_getscheduler(0));
}

// ── Logging (1) ───────────────────────────────────────────────────────
//
// Level is stored but not yet applied to __android_log — that API
// (__android_log_set_min_prio) requires API 30+, while minSdk is 26.
// GROWTH: apply conditionally once a version-gated path exists, or via
// the older android_set_log_level for API 26–29.

JNIEXPORT void JNICALL
Java_com_futura_engine_NativeEngine_nativeSetLogLevel(
    JNIEnv* /*env*/, jobject /*thiz*/, jint level) {
    g_log_level.store(static_cast<int>(level), std::memory_order_relaxed);
}

}  // extern "C"
