// futura-audio/engine/src/main/cpp/jni/CrashHandlerJni.cpp
//
// JNI for NativeEngine.installCrashHandler — 1 method.
// Installs POSIX signal handlers that write last_crash.txt on native crash.
// Report filename MUST match NativeEngine.kt's CRASH_REPORT_FILENAME.

#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

namespace {

constexpr const char* LOG_TAG       = "CrashHandlerJni";
constexpr const char* REPORT_NAME   = "last_crash.txt";
constexpr int         MAX_PATH_LEN  = 512;
constexpr int         WRITE_BUF_LEN = 512;

// State captured at install time. Global because signal handlers can't
// receive a context pointer.
int   g_report_fd = -1;
char  g_report_path[MAX_PATH_LEN] = {0};

struct sigaction g_prev_sigsegv;
struct sigaction g_prev_sigabrt;
struct sigaction g_prev_sigbus;
struct sigaction g_prev_sigill;
struct sigaction g_prev_sigfpe;

pthread_mutex_t g_install_mutex = PTHREAD_MUTEX_INITIALIZER;
bool g_installed = false;

// Async-signal-safe: writes are to a pre-opened fd, no allocation, no
// stdio, no locks. Only `write`, `syscall(SYS_gettid)`, and `close`.
void safe_write(int fd, const char* buf, size_t len) {
    size_t written = 0;
    while (written < len) {
        ssize_t r = write(fd, buf + written, len - written);
        if (r <= 0) return;  // give up on error
        written += static_cast<size_t>(r);
    }
}

void safe_write_cstr(int fd, const char* s) {
    size_t len = 0;
    while (s[len] != '\0' && len < WRITE_BUF_LEN) ++len;
    safe_write(fd, s, len);
}

const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGFPE:  return "SIGFPE";
        default:      return "UNKNOWN";
    }
}

// Format a signed int into a caller-provided buffer. Async-signal-safe.
int format_int(char* buf, int value) {
    char tmp[16];
    int n = 0;
    bool neg = value < 0;
    if (neg) value = -value;
    if (value == 0) { buf[0] = '0'; return 1; }
    while (value > 0) { tmp[n++] = static_cast<char>('0' + (value % 10)); value /= 10; }
    int out = 0;
    if (neg) buf[out++] = '-';
    while (n > 0) buf[out++] = tmp[--n];
    return out;
}

// Format an unsigned long long (hex) into a caller-provided buffer.
int format_hex(char* buf, unsigned long long value) {
    static const char kHex[] = "0123456789abcdef";
    char tmp[20];
    int n = 0;
    if (value == 0) { buf[0] = '0'; return 1; }
    while (value > 0 && n < 20) { tmp[n++] = kHex[value & 0xF]; value >>= 4; }
    int out = 0;
    buf[out++] = '0';
    buf[out++] = 'x';
    while (n > 0) buf[out++] = tmp[--n];
    return out;
}

void write_crash_report(int sig, siginfo_t* info) {
    if (g_report_fd < 0) return;

    char buf[WRITE_BUF_LEN];
    int n = 0;

    // "signal: NAME (SIG) addr=0x... tid=N\n"
    safe_write_cstr(g_report_fd, "signal: ");
    safe_write_cstr(g_report_fd, signal_name(sig));

    n = 0;
    buf[n++] = ' ';
    buf[n++] = '(';
    n += format_int(buf + n, sig);
    buf[n++] = ')';
    safe_write(g_report_fd, buf, static_cast<size_t>(n));

    if (info != nullptr) {
        safe_write_cstr(g_report_fd, " addr=");
        n = format_hex(buf, reinterpret_cast<unsigned long long>(info->si_addr));
        safe_write(g_report_fd, buf, static_cast<size_t>(n));
    }

    safe_write_cstr(g_report_fd, " tid=");
    n = format_int(buf, static_cast<int>(syscall(SYS_gettid)));
    safe_write(g_report_fd, buf, static_cast<size_t>(n));

    safe_write_cstr(g_report_fd, "\n");
}

// Shared handler body — writes report, then chains to previous handler.
void handle_signal(int sig, siginfo_t* info, void* context,
                   const struct sigaction* prev) {

    write_crash_report(sig, info);

    // Restore the previous handler and re-raise so the OS produces its
    // default crash output (logcat tombstone, debugger break, etc.).
    // This preserves the platform's normal crash diagnostics.
    if (prev != nullptr && prev->sa_flags & SA_SIGINFO) {
        prev->sa_sigaction(sig, info, context);
    } else if (prev != nullptr && prev->sa_handler != SIG_DFL
                                && prev->sa_handler != SIG_IGN) {
        prev->sa_handler(sig);
    } else {
        // Default action: re-raise with default disposition.
        signal(sig, SIG_DFL);
        raise(sig);
    }
}

void handler_segv(int sig, siginfo_t* info, void* ctx) { handle_signal(sig, info, ctx, &g_prev_sigsegv); }
void handler_abrt(int sig, siginfo_t* info, void* ctx) { handle_signal(sig, info, ctx, &g_prev_sigabrt); }
void handler_bus (int sig, siginfo_t* info, void* ctx) { handle_signal(sig, info, ctx, &g_prev_sigbus);  }
void handler_ill (int sig, siginfo_t* info, void* ctx) { handle_signal(sig, info, ctx, &g_prev_sigill);  }
void handler_fpe (int sig, siginfo_t* info, void* ctx) { handle_signal(sig, info, ctx, &g_prev_sigfpe);  }

void install_one(int sig, void (*handler)(int, siginfo_t*, void*),
                 struct sigaction* prev) {
    struct sigaction action;
    std::memset(&action, 0, sizeof(action));
    action.sa_sigaction = handler;
    action.sa_flags     = SA_SIGINFO | SA_RESTART;
    sigemptyset(&action.sa_mask);
    sigaction(sig, &action, prev);
}

}  // anonymous namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_futura_engine_NativeEngine_nativeInstallCrashHandler(
    JNIEnv* env, jobject /*thiz*/, jstring crashLogDir) {

    if (crashLogDir == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeInstallCrashHandler: null dir");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_install_mutex);

    if (g_installed) {
        pthread_mutex_unlock(&g_install_mutex);
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeInstallCrashHandler: already installed");
        return JNI_FALSE;
    }

    const char* dir = env->GetStringUTFChars(crashLogDir, nullptr);
    if (dir == nullptr) {
        pthread_mutex_unlock(&g_install_mutex);
        return JNI_FALSE;
    }

    // Build "<dir>/last_crash.txt". Truncate silently if over MAX_PATH_LEN.
    int written = 0;
    size_t dirlen = 0;
    while (dir[dirlen] != '\0' && written < MAX_PATH_LEN - 1) {
        g_report_path[written++] = dir[dirlen++];
    }
    env->ReleaseStringUTFChars(crashLogDir, dir);

    if (written > 0 && g_report_path[written - 1] != '/') {
        g_report_path[written++] = '/';
    }
    for (const char* p = REPORT_NAME; *p != '\0' && written < MAX_PATH_LEN - 1; ++p) {
        g_report_path[written++] = *p;
    }
    g_report_path[written] = '\0';

    // O_APPEND so consecutive crashes within one process append rather
    // than overwrite; O_CREAT so the first crash creates it.
    g_report_fd = open(g_report_path,
                       O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (g_report_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeInstallCrashHandler: open failed (%d) for %s",
            errno, g_report_path);
        pthread_mutex_unlock(&g_install_mutex);
        return JNI_FALSE;
    }

    install_one(SIGSEGV, handler_segv, &g_prev_sigsegv);
    install_one(SIGABRT, handler_abrt, &g_prev_sigabrt);
    install_one(SIGBUS,  handler_bus,  &g_prev_sigbus);
    install_one(SIGILL,  handler_ill,  &g_prev_sigill);
    install_one(SIGFPE,  handler_fpe,  &g_prev_sigfpe);

    g_installed = true;
    pthread_mutex_unlock(&g_install_mutex);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "nativeInstallCrashHandler: installed, report -> %s", g_report_path);
    return JNI_TRUE;
}

}  // extern "C"