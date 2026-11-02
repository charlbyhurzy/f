// futura-audio/engine/src/main/cpp/jni/UsbAudioJni.cpp
//
// JNI for NativeUsbAudio.kt — 3 methods.
// Growth seam: usb/UsbDacPassthrough.h (tree) supplies the real libusb backend.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>

namespace {

constexpr const char* LOG_TAG = "UsbAudioJni";
constexpr int STATE_INT_COUNT = 6;

// State array layout — must match NativeUsbAudio.kt's nativeGetState() KDoc.
constexpr int IDX_ATTACHED    = 0;
constexpr int IDX_SAMPLE_RATE = 1;
constexpr int IDX_BIT_DEPTH   = 2;
constexpr int IDX_CHANNELS    = 3;
constexpr int IDX_BIT_PERFECT = 4;
constexpr int IDX_ERROR_CODE  = 5;

// Error codes — must match UsbAudioError.nativeValue.
constexpr int ERR_NONE                   = 0;
constexpr int ERR_INVALID_FD             = 1;
constexpr int ERR_LIBUSB_INIT_FAILED     = 2;
constexpr int ERR_NOT_UAC_COMPLIANT      = 3;
constexpr int ERR_UNSUPPORTED_FORMAT     = 4;
constexpr int ERR_INTERFACE_CLAIM_FAILED = 5;
constexpr int ERR_STREAM_OPEN_FAILED     = 6;

struct UsbState {
    std::atomic<bool>    attached{false};
    std::atomic<int32_t> sample_rate{0};
    std::atomic<int32_t> bit_depth{0};
    std::atomic<int32_t> channels{0};
    std::atomic<bool>    bit_perfect{false};
    std::atomic<int32_t> error_code{ERR_NONE};
};

UsbState g_state;

void reset_negotiated_fields() {
    g_state.sample_rate.store(0, std::memory_order_relaxed);
    g_state.bit_depth.store(0, std::memory_order_relaxed);
    g_state.channels.store(0, std::memory_order_relaxed);
    g_state.bit_perfect.store(false, std::memory_order_relaxed);
}

}  // anonymous namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativeUsbAudio_nativeAttach(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint fd, jint preferred_sample_rate,
    jint preferred_bit_depth, jint preferred_channels) {

    g_state.attached.store(false, std::memory_order_relaxed);
    reset_negotiated_fields();
    g_state.error_code.store(ERR_NONE, std::memory_order_relaxed);

    if (fd < 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeAttach: invalid fd %d", static_cast<int>(fd));
        g_state.error_code.store(ERR_INVALID_FD, std::memory_order_relaxed);
        return JNI_FALSE;
    }

    // GROWTH: usb/UsbDacPassthrough.h (not yet delivered) will:
    //   1. libusb_init() + libusb_wrap_sys_device(fd)
    //   2. Enumerate UAC streaming interfaces
    //   3. Negotiate sample_rate / bit_depth / channels
    //      (device default when preferred_* == 0)
    //   4. Open isochronous playback endpoint
    //   5. Populate g_state, set attached = true
    //
    // The fd is BORROWED, not owned — never close() it here. See
    // NativeUsbAudio.kt's "File descriptor ownership" note.
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
        "nativeAttach: libusb backend not available — requested "
        "%dHz/%dbit/%dch on fd %d",
        static_cast<int>(preferred_sample_rate),
        static_cast<int>(preferred_bit_depth),
        static_cast<int>(preferred_channels),
        static_cast<int>(fd));

    g_state.error_code.store(ERR_LIBUSB_INIT_FAILED, std::memory_order_relaxed);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_futura_engine_NativeUsbAudio_nativeDetach(
    JNIEnv* /*env*/, jobject /*thiz*/) {

    // GROWTH: UsbDacPassthrough.h teardown (cancel isochronous transfers,
    // release UAC interface, unwrap libusb handle). Do NOT close fd.
    g_state.attached.store(false, std::memory_order_relaxed);
    reset_negotiated_fields();
    // error_code is intentionally preserved — see UsbAudioState KDoc
    // in NativeUsbAudio.kt ("preserved across successful detach calls").
}

JNIEXPORT jintArray JNICALL
Java_com_futura_engine_NativeUsbAudio_nativeGetState(
    JNIEnv* env, jobject /*thiz*/) {

    jint local[STATE_INT_COUNT];
    local[IDX_ATTACHED]    = g_state.attached.load(std::memory_order_relaxed) ? 1 : 0;
    local[IDX_SAMPLE_RATE] = g_state.sample_rate.load(std::memory_order_relaxed);
    local[IDX_BIT_DEPTH]   = g_state.bit_depth.load(std::memory_order_relaxed);
    local[IDX_CHANNELS]    = g_state.channels.load(std::memory_order_relaxed);
    local[IDX_BIT_PERFECT] = g_state.bit_perfect.load(std::memory_order_relaxed) ? 1 : 0;
    local[IDX_ERROR_CODE]  = g_state.error_code.load(std::memory_order_relaxed);

    jintArray result = env->NewIntArray(STATE_INT_COUNT);
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, STATE_INT_COUNT, local);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

}  // extern "C"