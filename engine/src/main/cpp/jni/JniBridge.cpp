#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr jint kStateIdle = 1;
constexpr jint kStateReady = 2;
constexpr jint kStatePlaying = 3;
constexpr jint kStatePaused = 4;
constexpr jint kStateEnded = 5;

constexpr jint kStreamUninitialized = 0;
constexpr jint kStreamStarted = 2;
constexpr jint kStreamPaused = 3;
constexpr jint kStreamStopped = 5;

struct PlayerState {
    bool created = false;
    bool prepared = false;
    bool playing = false;
    bool loopEnabled = false;
    bool bitPerfectEnabled = false;
    bool silenceTrimEnabled = false;
    bool nextPrepared = false;
    float volumeLinear = 1.0f;
    float playbackSpeed = 1.0f;
    float pitchSemitones = 0.0f;
    jint resamplerQuality = 1;
    jlong positionMs = 0;
    jlong durationMs = -1;
    jlong loopStartMs = 0;
    jlong loopEndMs = 0;
    jint playbackState = kStateIdle;
    jint streamState = kStreamUninitialized;
    std::string sourceUri;
    std::string sourceFormat;
    std::string lastError;
    std::vector<std::string> queue;
};

PlayerState g_state;
std::mutex g_mutex;

bool readyLocked() {
    return g_state.created && g_state.prepared;
}

void setErrorLocked(const char* message) {
    g_state.lastError = message;
    g_state.playbackState = 6;
}

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

float linearToDb(float linear) {
    return linear <= 0.0000158489f ? -96.0f : 20.0f * std::log10(linear);
}

jstring newString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string readString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeCreate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.created = true;
    g_state.lastError.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeDestroy(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state = PlayerState{};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativePrepare(
    JNIEnv* env, jobject, jstring uri, jint) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state.created) {
        setErrorLocked("player is not created");
        return JNI_FALSE;
    }
    g_state.sourceUri = readString(env, uri);
    if (g_state.sourceUri.empty()) {
        setErrorLocked("source URI is empty");
        return JNI_FALSE;
    }
    g_state.prepared = false;
    g_state.playing = false;
    g_state.positionMs = 0;
    g_state.durationMs = -1;
    g_state.playbackState = 6;
    g_state.streamState = kStreamStopped;
    g_state.sourceFormat.clear();
    g_state.lastError = "decoder backend unavailable";
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeReset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state.created) return;
    g_state.prepared = false;
    g_state.playing = false;
    g_state.positionMs = 0;
    g_state.durationMs = -1;
    g_state.queue.clear();
    g_state.nextPrepared = false;
    g_state.playbackState = kStateIdle;
    g_state.streamState = kStreamStopped;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeIsPrepared(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return readyLocked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativePlay(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked()) return JNI_FALSE;
    g_state.playing = true;
    g_state.playbackState = kStatePlaying;
    g_state.streamState = kStreamStarted;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativePause(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked()) return JNI_FALSE;
    g_state.playing = false;
    g_state.playbackState = kStatePaused;
    g_state.streamState = kStreamPaused;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked()) return JNI_FALSE;
    g_state.playing = false;
    g_state.positionMs = 0;
    g_state.playbackState = kStateReady;
    g_state.streamState = kStreamStopped;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSeekTo(JNIEnv*, jobject, jlong positionMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked()) return JNI_FALSE;
    g_state.positionMs = std::max<jlong>(0, positionMs);
    if (g_state.durationMs >= 0) {
        g_state.positionMs = std::min(g_state.positionMs, g_state.durationMs);
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeIsPlaying(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.playing ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetPositionMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.positionMs;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetDurationMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.durationMs;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetPlaybackState(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.playbackState;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetStreamState(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.streamState;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetVolumeLinear(JNIEnv*, jobject, jfloat volume) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.volumeLinear = std::clamp(volume, 0.0f, 1.0f);
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetVolumeDb(JNIEnv*, jobject, jfloat db) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.volumeLinear = std::clamp(dbToLinear(std::clamp(db, -96.0f, 0.0f)), 0.0f, 1.0f);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetVolumeLinear(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.volumeLinear;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetPlaybackSpeed(JNIEnv*, jobject, jfloat speed) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked()) return JNI_FALSE;
    g_state.playbackSpeed = std::clamp(speed, 0.5f, 3.0f);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetPlaybackSpeed(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.playbackSpeed;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetPitchSemitones(JNIEnv*, jobject, jfloat semitones) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked()) return JNI_FALSE;
    g_state.pitchSemitones = std::clamp(semitones, -24.0f, 24.0f);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetPitchSemitones(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.pitchSemitones;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetLoopAB(JNIEnv*, jobject, jlong startMs, jlong endMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked() || startMs < 0 || endMs <= startMs) return JNI_FALSE;
    g_state.loopStartMs = startMs;
    g_state.loopEndMs = endMs;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeClearLoopAB(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.loopStartMs = 0;
    g_state.loopEndMs = 0;
    g_state.loopEnabled = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetLoopEnabled(JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.loopEnabled = enabled == JNI_TRUE && g_state.loopEndMs > g_state.loopStartMs;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeIsLoopEnabled(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.loopEnabled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeEnqueueNext(
    JNIEnv* env, jobject, jstring uri, jint) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!readyLocked()) return JNI_FALSE;
    const std::string next = readString(env, uri);
    if (next.empty()) return JNI_FALSE;
    g_state.queue.push_back(next);
    g_state.nextPrepared = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeClearQueue(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.queue.clear();
    g_state.nextPrepared = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetQueueSize(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jint>(g_state.queue.size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSkipToNext(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state.queue.empty()) return JNI_FALSE;
    g_state.sourceUri = g_state.queue.front();
    g_state.queue.erase(g_state.queue.begin());
    g_state.nextPrepared = !g_state.queue.empty();
    g_state.positionMs = 0;
    g_state.playbackState = kStateReady;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeIsNextPrepared(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.nextPrepared ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetSourceFormat(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return newString(env, g_state.sourceFormat);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetSourceSampleRate(JNIEnv*, jobject) {
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetSourceChannels(JNIEnv*, jobject) {
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetSourceBitDepth(JNIEnv*, jobject) {
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetBitPerfectEnabled(JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.bitPerfectEnabled = enabled == JNI_TRUE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeIsBitPerfectEnabled(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.bitPerfectEnabled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetResamplerQuality(JNIEnv*, jobject, jint quality) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.resamplerQuality = std::clamp(quality, 0, 3);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetResamplerQuality(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.resamplerQuality;
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeSetSilenceTrimEnabled(JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.silenceTrimEnabled = enabled == JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeIsSilenceTrimEnabled(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.silenceTrimEnabled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return newString(env, g_state.lastError);
}

extern "C" JNIEXPORT void JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeClearLastError(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_state.lastError.clear();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetBufferedPositionMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.prepared ? g_state.positionMs : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetLoadedMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.prepared ? g_state.positionMs : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetOutputSampleRate(JNIEnv*, jobject) {
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetOutputChannels(JNIEnv*, jobject) {
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_futura_engine_NativePlayerBridge_nativeGetRenderThreadId(JNIEnv*, jobject) {
    return -1;
}
