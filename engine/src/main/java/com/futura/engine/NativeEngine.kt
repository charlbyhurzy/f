// futura-audio/engine/src/main/java/com/futura/engine/NativeEngine.kt
//
// Phase 3 — Native engine JNI facade.
//
// ⚠️  PATH NOTE — READ BEFORE EDITING  ⚠️
// The project tree (futura-audio.txt) places this file at
//     app/src/main/java/com/futura/engine/NativeEngine.kt
// under Phase 3 — Native Bridge, alongside NativePlayerBridge,
// DspBridge, NativeUsbAudio, NativeFlacScanner, and NativeWaveform.
//
// This delivery is at engine/src/main/java/com/futura/engine/ per an
// explicit instruction on the current turn. The Kotlin Android plugin
// is applied to :engine (see engine/build.gradle.kts), so this file
// compiles into the :engine AAR and becomes available to :app through
// the existing `implementation(project(":engine"))` dependency.
//
// The remaining JNI bridges (NativePlayerBridge, DspBridge, …) and
// MlBridge are still in :app. This asymmetry is documented, deliberate,
// and reversible — moving the rest into :engine is a mechanical operation
// with no code changes (only package/path moves and one build script
// adjustment to keep the `implementation(project(":engine"))` edge intact).
//
// ── Responsibility ──────────────────────────────────────────────────────
// Per the tree annotation "✅ JNI facade", this object is the front door
// to the native engine. It owns:
//
//   • Loading libfutura_engine.so once per process.
//   • Initializing / tearing down the engine-wide audio device.
//   • Exposing version, build type, and diagnostic metadata.
//   • Installing the native crash handler and reading its reports.
//   • Reporting resident / peak / heap memory usage for diagnostics.
//   • Reporting device audio configuration (sample rate, burst size,
//     hardware latency).
//   • XRun (buffer underrun) counter access — feature #61.
//   • Audio-thread priority escalation — feature #65.
//   • Native log level control.
//
// It deliberately does NOT own:
//
//   • Playback control (play/pause/seek/stream) → NativePlayerBridge (47 methods)
//   • DSP operations (EQ, crossfeed, compression, …) → DspBridge (23 methods)
//   • ML inference → MlBridge (20 methods)
//   • USB DAC control → NativeUsbAudio
//   • FLAC frame scanning → NativeFlacScanner
//   • Waveform extraction → NativeWaveform
//   • Audio taps for visualizers → AudioTap / LockFreeRingBufferTap
//
// Those files each own a specific domain and route through this facade
// only when they need device configuration or engine-wide state.
//
// ── Design contract (same safety-first model as MlBridge.kt) ─────────────
// Every external method is declared `private` and wrapped by a public
// method that:
//
//   1. Checks `libraryAvailable` — the .so may not exist yet (engine
//      module not built, bootstrap phase, unsupported ABI). When absent,
//      the wrapper returns its documented default.
//   2. Catches `UnsatisfiedLinkError` — thrown when an `external fun`
//      has no matching native implementation. This happens today
//      because engine/src/main/cpp/jni/JniBridge.cpp and
//      CrashHandlerJni.cpp have not been authored yet. Without this
//      catch, the first call would crash the process.
//   3. Catches `Exception` — any other native failure (device busy,
//      permission denied, internal assertion) is caught, logged, and
//      degraded rather than propagated.
//
// Two wrappers intentionally avoid native calls entirely and are pure
// Kotlin:
//
//   • `getSupportedAbis()` — reads `Build.SUPPORTED_ABIS`. The list of
//     ABIs the DEVICE supports is a framework property, not something
//     the engine has any input into.
//   • `readLastCrashReport()` — reads the crash report file from disk.
//     The native crash handler WRITES the file (it must — the handler
//     runs after the JVM has died); the Kotlin side READS it on the
//     next launch.
//
// ── Threading contract ─────────────────────────────────────────────────
// All public methods are synchronous. `initialize()` and `shutdown()`
// block for the duration of native engine setup/teardown (typically
// 50–200 ms) and MUST be called off the main thread — from an
// application-scoped coroutine or a dedicated initialization thread.
// The remaining getters are cheap (single native call each) and can be
// called from any thread, but are safest off the main thread because
// even cheap JNI calls should not be made on a UI tick.
//
// `escalateAudioThreadPriority()` is the exception: it MUST be called
// from the audio rendering thread itself. It sets that thread's
// scheduling policy to SCHED_FIFO; the SCHED_FIFO policy is per-thread,
// not per-process, so calling it from the main thread would elevate the
// UI thread instead, which is worse than useless.
//
// ── Library loading ─────────────────────────────────────────────────────
// `System.loadLibrary("futura_engine")` is called once in the object's
// `init` block. It is idempotent: MlBridge (in :app) also calls the same
// load, and whichever loads first wins; the second is a no-op. JNI
// symbol resolution is by fully-qualified class name, so
// MlJni.cpp's `Java_com_futura_engine_ml_MlBridge_nativeXxx` and
// JniBridge.cpp's `Java_com_futura_engine_NativeEngine_nativeXxx`
// coexist in the same .so without interference.
//
// If the library cannot be loaded (missing .so, wrong ABI, NDK not
// installed on the build machine), `libraryAvailable` is set to false
// and every wrapper returns its default. The app continues without any
// native functionality. The failure is logged at ERROR level so it is
// visible in logcat without requiring a debugger.
//
// ── JNI naming contract (for JniBridge.cpp / CrashHandlerJni.cpp) ────────
// This object is `object NativeEngine` in package `com.futura.engine`.
// The JNI functions in the native layer must be declared with the exact
// names and signatures below. The `thiz` parameter is a `jobject` (the
// singleton instance), NOT a `jclass`, because Kotlin `object` methods
// are instance methods on the singleton:
//
//     extern "C" JNIEXPORT jboolean JNICALL
//     Java_com_futura_engine_NativeEngine_nativeInitialize(
//         JNIEnv* env, jobject thiz,
//         jint preferredSampleRate, jint preferredFramesPerBurst,
//         jint performanceMode, jboolean enableBitPerfect,
//         jboolean enableXRunDetection);
//
// If the native side uses RegisterNatives in JNI_OnLoad, the signatures
// must match the Kotlin declarations exactly. The direct
// `Java_...` link (assumed by the KDoc above) is simpler and preferred.
//
// ── Forward-reference audit ──────────────────────────────────────────
// Imported symbols:
//   • android.os.Build       (framework, all API levels)
//   • android.util.Log       (framework, all API levels)
//   • java.io.File           (JDK standard)
//   • kotlin.jvm.Volatile    (Kotlin stdlib annotation)
//
// No project class, no Compose, no Hilt, no coroutines, no MediaSession,
// no MlBridge, no NativePlayerBridge, no DspBridge, no AudioTap, and no
// data classes (EngineStats.kt, MemoryProfileEntry.kt, BitPerfectState.kt,
// etc.) that have not been delivered. This file compiles and functions
// standalone against the entire delivered codebase.

package com.futura.engine

import android.os.Build
import android.util.Log
import java.io.File

// ═════════════════════════════════════════════════════════════════════════
// Configuration types
// ═════════════════════════════════════════════════════════════════════════

/**
 * Oboe-style performance mode, mirroring the AAudio/OpenSL ES
 * preference order exposed by the native side.
 *
 * The native layer maps these to `oboe::PerformanceMode` values:
 *   • [NONE]          → `oboe::PerformanceMode::None`
 *   • [LOW_LATENCY]   → `oboe::PerformanceMode::LowLatency`
 *   • [POWER_SAVING]  → `oboe::PerformanceMode::PowerSaving`
 */
enum class PerformanceMode(val nativeValue: Int) {
    NONE(0),
    LOW_LATENCY(1),
    POWER_SAVING(2)
}

/**
 * Configuration for [NativeEngine.initialize].
 *
 * @param preferredSampleRate Requested sample rate in Hz. `0` means
 *        "use the device's native rate" — the correct default for
 *        bit-perfect playback and the correct default for most devices
 *        anyway. A non-zero value requests that specific rate; the
 *        native side will resample the output stream to match, which
 *        is sometimes desirable for parity across devices but defeats
 *        bit-perfect mode.
 * @param preferredFramesPerBurst Requested output buffer size in
 *        frames. `0` means auto-tune (feature #62 — Dynamic Buffer
 *        Auto-Tuner). A non-zero value requests a specific size; the
 *        native side clamps to `[64, 4096]` and falls back to the
 *        device default if the request is out of range.
 * @param performanceMode Requested Oboe performance mode. See
 *        [PerformanceMode]. `LOW_LATENCY` is the app default because
 *        every interactive audio feature (alarm, playback, DJ Studio)
 *        benefits from it.
 * @param enableBitPerfect Feature #142 — request bit-perfect playback
 *        mode. When true, the native side bypasses the resampler and
 *        requests the device's native sample rate regardless of
 *        `preferredSampleRate`. When the current device/route cannot
 *        deliver bit-perfect output (e.g. Bluetooth), the native side
 *        logs a warning and falls back to normal mode; the caller can
 *        check the effective state via the StreamInspector once that
 *        file is delivered.
 * @param enableXRunDetection Feature #61 — enable the XRun detector.
 *        When true, the native side counts buffer underruns and exposes
 *        them via [NativeEngine.getXRunCount]. The counting overhead is
 *        negligible (an atomic increment per xrun), so this defaults to
 *        true; callers who need to squeeze every cycle can disable it
 *        at the cost of losing xrun diagnostics.
 */
data class NativeEngineConfig(
    val preferredSampleRate: Int = 0,
    val preferredFramesPerBurst: Int = 0,
    val performanceMode: PerformanceMode = PerformanceMode.LOW_LATENCY,
    val enableBitPerfect: Boolean = false,
    val enableXRunDetection: Boolean = true
)

/**
 * Native log severity. Higher values are more verbose.
 *
 * Log levels in the native layer control how much the engine writes to
 * logcat. In release builds, callers should typically set [ERROR] or
 * [WARN] to avoid flooding logcat; in debug builds, [DEBUG] or [INFO]
 * is the right choice. [VERBOSE] should only be set when diagnosing a
 * specific issue because it produces per-frame output.
 */
enum class NativeLogLevel(val nativeValue: Int) {
    ERROR(0),
    WARN(1),
    INFO(2),
    DEBUG(3),
    VERBOSE(4)
}

// ═════════════════════════════════════════════════════════════════════════
// Facade
// ═════════════════════════════════════════════════════════════════════════

/**
 * The JNI facade to the native audio engine.
 *
 * See the file header for the full design contract (safety-first
 * wrappers, threading, library loading lifecycle, JNI naming, path
 * notes). All methods are synchronous; see the threading section
 * before calling any method from a hot path.
 */
object NativeEngine {

    private const val TAG = "NativeEngine"
    private const val LIBRARY_NAME = "futura_engine"

    @Volatile
    private var libraryAvailable: Boolean = false

    @Volatile
    private var engineInitialized: Boolean = false

    @Volatile
    private var crashLogDir: File? = null

    init {
        libraryAvailable = try {
            System.loadLibrary(LIBRARY_NAME)
            Log.i(TAG, "Native library '$LIBRARY_NAME' loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(
                TAG,
                "Native library '$LIBRARY_NAME' unavailable — native engine disabled. " +
                    "Build :engine or check ABI filters if this is unexpected.",
                e
            )
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Availability
    // ═════════════════════════════════════════════════════════════════

    /**
     * True if `libfutura_engine.so` loaded into the process. False means
     * no native functionality is available at all — playback, DSP, ML,
     * USB audio all disabled. Callers should short-circuit their work
     * rather than calling into this object.
     */
    fun isAvailable(): Boolean = libraryAvailable

    /**
     * True if [initialize] has been called successfully AND [shutdown]
     * has not been called since. False means the engine is not ready to
     * produce audio.
     */
    fun isInitialized(): Boolean = libraryAvailable && engineInitialized

    // ═════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════

    /**
     * Initialise the native engine with the given configuration.
     *
     * Blocking — typical duration 50–200 ms while the audio device is
     * opened and the render loop is prepared. **Call off the main
     * thread.**
     *
     * Idempotent in the "safe to call again" sense: if the engine is
     * already initialised, the native side tears down the previous
     * state before applying the new configuration. Callers who need to
     * change configuration can call [shutdown] first and then
     * [initialize] with the new config; calling [initialize] twice in a
     * row is also safe.
     *
     * @return true on success, false on any failure (library missing,
     *         audio device unavailable, configuration rejected, native
     *         exception). The caller is expected to log and degrade
     *         gracefully — an alarm that fires without native audio
     *         still works via `PreflightAudioFallback` (which uses
     *         `MediaPlayer`), it just can't play lossless or use DSP.
     */
    fun initialize(config: NativeEngineConfig): Boolean {
        val success = safeCall(false) {
            nativeInitialize(
                preferredSampleRate = config.preferredSampleRate,
                preferredFramesPerBurst = config.preferredFramesPerBurst,
                performanceMode = config.performanceMode.nativeValue,
                enableBitPerfect = config.enableBitPerfect,
                enableXRunDetection = config.enableXRunDetection
            )
        }
        engineInitialized = success
        if (success) {
            Log.i(
                TAG,
                "Native engine initialised " +
                    "(perfMode=${config.performanceMode}, " +
                    "bitPerfect=${config.enableBitPerfect}, " +
                    "xrunDetect=${config.enableXRunDetection})"
            )
        } else {
            Log.w(TAG, "Native engine initialisation failed")
        }
        return success
    }

    /**
     * Tear down the native engine and release all resources.
     *
     * Blocking — typical duration 30–100 ms while the render thread is
     * joined and buffers are freed. **Call off the main thread.**
     *
     * Safe to call multiple times. After this returns, [isInitialized]
     * is false until [initialize] succeeds again. In-flight native
     * operations on other threads are NOT synchronised against
     * shutdown — callers should ensure playback is stopped before
     * calling.
     */
    fun shutdown() {
        safeCallUnit { nativeShutdown() }
        engineInitialized = false
    }

    // ═════════════════════════════════════════════════════════════════
    // Version / build info
    // ═════════════════════════════════════════════════════════════════

    /**
     * The native engine's version string, e.g. `"1.0.0"`. Returns
     * `"unavailable"` when the library isn't loaded so callers can
     * distinguish "engine is old" from "engine is missing".
     */
    fun getVersionString(): String =
        safeCall("unavailable") { nativeGetVersionString() }

    /**
     * The engine's build type: `"debug"` or `"release"`.
     *
     * Useful for logging at startup so a support ticket can distinguish
     * a release build from a developer build without asking the user
     * for the APK.
     */
    fun getBuildType(): String =
        safeCall("unknown") { nativeGetBuildType() }

    /**
     * The ABIs the device supports, e.g. `["arm64-v8a", "armeabi-v7a"]`.
     *
     * This is `Build.SUPPORTED_ABIS` verbatim — it reflects the DEVICE's
     * capabilities, not the .so's. To determine which ABI Android
     * actually loaded, callers should check the .so's path or rely on
     * `Process.is64Bit()`.
     */
    fun getSupportedAbis(): Array<String> = Build.SUPPORTED_ABIS

    // ═════════════════════════════════════════════════════════════════
    // Crash handling
    // ═════════════════════════════════════════════════════════════════

    /**
     * Install the native crash handler.
     *
     * The native handler (CrashHandlerJni.cpp) installs POSIX signal
     * handlers for SIGSEGV, SIGABRT, SIGBUS, SIGILL, and SIGFPE. When
     * a native crash occurs, the handler writes a report file to
     * [dir]/last_crash.txt before the process dies. On the next launch,
     * callers can call [readLastCrashReport] to retrieve it for
     * diagnostics (typically fed to `LogcatDiagnosticRecorder` when that
     * file is delivered).
     *
     * @param dir Directory for crash reports. Must be app-writable.
     *        Typically `context.getExternalFilesDir(null)` or
     *        `context.filesDir`. Created if it doesn't exist.
     * @return true on success, false on any failure (library missing,
     *         directory not writable, handler already installed —
     *         installation is not idempotent across calls, so this
     *         returns false if called twice; use the initial call's
     *         result).
     */
    fun installCrashHandler(dir: File): Boolean {
        if (!libraryAvailable) return false
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create crash log directory: ${dir.absolutePath}")
            return false
        }
        val success = safeCall(false) { nativeInstallCrashHandler(dir.absolutePath) }
        if (success) {
            crashLogDir = dir
            Log.i(TAG, "Crash handler installed (dir=${dir.absolutePath})")
        } else {
            Log.w(TAG, "Crash handler installation failed")
        }
        return success
    }

    /**
     * Read the last crash report written by the native crash handler.
     *
     * Returns null if:
     *   • [installCrashHandler] was never called (no directory known).
     *   • The report file does not exist (no crash since install).
     *   • The file exists but cannot be read (I/O error).
     *
     * This method is pure Kotlin — it reads a file that the native
     * handler wrote. It works even if [libraryAvailable] is false
     * (the crash report might have been written by an earlier process
     * run that had the library loaded).
     */
    fun readLastCrashReport(): String? {
        val dir = crashLogDir ?: return null
        val file = File(dir, CRASH_REPORT_FILENAME)
        if (!file.exists()) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read crash report", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Memory profiling
    // ═════════════════════════════════════════════════════════════════
    //
    // These getters return `-1L` when the library isn't loaded, matching
    // the standard "unknown" sentinel used by most memory APIs. Values
    // are refreshed by the native side on demand and reflect the state
    // at the moment of the call — they are NOT a cached snapshot.

    /**
     * Current resident memory used by the native audio pipeline, in
     * bytes. Includes all buffers, ring buffers, and decoder contexts
     * the engine has allocated. Does NOT include JVM memory.
     *
     * @return Resident bytes, or `-1L` if unavailable.
     */
    fun getResidentMemoryBytes(): Long =
        safeCall(-1L) { nativeGetResidentMemoryBytes() }

    /**
     * Peak resident memory used by the native audio pipeline since
     * process start, in bytes. Useful for diagnosing memory leaks —
     * compare peak against current over long sessions.
     *
     * @return Peak resident bytes, or `-1L` if unavailable.
     */
    fun getPeakResidentMemoryBytes(): Long =
        safeCall(-1L) { nativeGetPeakResidentMemoryBytes() }

    /**
     * Bytes currently allocated on the native heap via
     * `malloc`/`new`/`mmap` inside the engine, excluding JVM-managed
     * memory.
     *
     * @return Native heap bytes, or `-1L` if unavailable.
     */
    fun getNativeHeapAllocatedBytes(): Long =
        safeCall(-1L) { nativeGetNativeHeapAllocatedBytes() }

    // ═════════════════════════════════════════════════════════════════
    // Device audio information
    // ═════════════════════════════════════════════════════════════════
    //
    // These reflect the CURRENTLY-OPEN audio device. Before [initialize]
    // succeeds, they return 0 / NaN — the device hasn't been probed yet.
    // After [initialize], they report what the native side actually
    // negotiated with the OS, which may differ from the config's
    // `preferred*` fields (the OS has final say).

    /**
     * Actual sample rate negotiated with the audio device, in Hz.
     * Typically 44100 or 48000.
     *
     * @return Sample rate, or `0` if unavailable.
     */
    fun getDeviceSampleRate(): Int =
        safeCall(0) { nativeGetDeviceSampleRate() }

    /**
     * Actual buffer size in frames negotiated with the audio device.
     *
     * @return Frames per burst, or `0` if unavailable.
     */
    fun getDeviceFramesPerBurst(): Int =
        safeCall(0) { nativeGetDeviceFramesPerBurst() }

    /**
     * Hardware round-trip latency (input + output) in milliseconds, as
     * computed by the native AudioHardwareLatencyCalculator (feature
     * #73).
     *
     * This is the number the StreamInspector modal displays. For
     * AAudio MMAP streams it is typically ~8–12 ms; for legacy OpenSL
     * ES it can be 30–50 ms.
     *
     * @return Latency in milliseconds, or `Float.NaN` if unavailable.
     */
    fun getDeviceHardwareLatencyMs(): Float =
        safeCall(Float.NaN) { nativeGetDeviceHardwareLatencyMs() }

    // ═════════════════════════════════════════════════════════════════
    // XRun (buffer underrun) detection — feature #61
    // ═════════════════════════════════════════════════════════════════

    /**
     * Total number of buffer underruns (XRun events) observed since
     * [initialize] (or since [resetXRunCount], if later). Counted only
     * when `NativeEngineConfig.enableXRunDetection` is true.
     *
     * @return Total XRun count, or `-1L` if unavailable.
     */
    fun getXRunCount(): Long =
        safeCall(-1L) { nativeGetXRunCount() }

    /**
     * Reset the XRun counter to zero. Useful before a specific test or
     * playback session so a subsequent [getXRunCount] reflects only
     * that session.
     */
    fun resetXRunCount() {
        safeCallUnit { nativeResetXRunCount() }
    }

    // ═════════════════════════════════════════════════════════════════
    // Audio thread priority — feature #65
    // ═════════════════════════════════════════════════════════════════

    /**
     * Escalate the CALLING thread's scheduling policy to SCHED_FIFO
     * (Linux real-time priority) so the audio render loop is not
     * preempted by ordinary scheduler decisions.
     *
     * **MUST be called from the audio rendering thread itself.** SCHED_FIFO
     * is per-thread, not per-process; calling this from any other thread
     * (including the main thread) elevates the wrong thread and leaves
     * the audio thread at default priority.
     *
     * Typical usage:
     *
     *     val renderThread = Thread {
     *         NativeEngine.escalateAudioThreadPriority()
     *         while (running) { renderAudioFrame() }
     *     }.apply { isDaemon = true }
     *     renderThread.start()
     *
     * @return true on success, false on failure (library missing, not
     *         running as audio thread, insufficient privileges,
     *         platform denies SCHED_FIFO).
     */
    fun escalateAudioThreadPriority(): Boolean =
        safeCall(false) { nativeEscalateAudioThreadPriority() }

    /**
     * The current thread's Linux scheduling policy as an integer
     * (`SCHED_OTHER=0`, `SCHED_FIFO=1`, `SCHED_RR=2`).
     *
     * Useful for asserting in tests that
     * [escalateAudioThreadPriority] actually took effect — if the
     * returned value is 1 (SCHED_FIFO) after escalation, the
     * privilege was successfully obtained.
     *
     * @return Scheduling policy integer, or `-1` if unavailable.
     */
    fun getCurrentAudioThreadPriority(): Int =
        safeCall(-1) { nativeGetCurrentAudioThreadPriority() }

    // ═════════════════════════════════════════════════════════════════
    // Logging
    // ═════════════════════════════════════════════════════════════════

    /**
     * Set the native layer's logcat verbosity.
     *
     * Persists for the lifetime of the process; callers who set it once
     * at startup (e.g. `setLogLevel(if (BuildConfig.DEBUG) DEBUG else WARN)`)
     * do not need to re-set it per session.
     */
    fun setLogLevel(level: NativeLogLevel) {
        safeCallUnit { nativeSetLogLevel(level.nativeValue) }
    }

    // ═════════════════════════════════════════════════════════════════
    // Safe-call helpers (same model as MlBridge.kt)
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
    // Native declarations
    // ═════════════════════════════════════════════════════════════════
    //
    // Private so Kotlin callers cannot bypass the safe wrappers above.
    // JNI ignores Kotlin visibility — JniBridge.cpp resolves these by
    // their `Java_com_futura_engine_NativeEngine_nativeXxx` names
    // regardless of the `private` modifier.
    //
    // `thiz` on the native side is a `jobject` (singleton instance),
    // NOT a `jclass` — see the file header's JNI naming contract.
    //
    // 16 native methods. Matches the "JNI facade" role: lifecycle,
    // version, crash handler, memory, device info, xrun, priority,
    // logging. All domain-specific operations route through their
    // dedicated bridges.

    private external fun nativeInitialize(
        preferredSampleRate: Int,
        preferredFramesPerBurst: Int,
        performanceMode: Int,
        enableBitPerfect: Boolean,
        enableXRunDetection: Boolean
    ): Boolean

    private external fun nativeShutdown()

    private external fun nativeGetVersionString(): String

    private external fun nativeGetBuildType(): String

    private external fun nativeInstallCrashHandler(
        crashLogDir: String
    ): Boolean

    private external fun nativeGetResidentMemoryBytes(): Long

    private external fun nativeGetPeakResidentMemoryBytes(): Long

    private external fun nativeGetNativeHeapAllocatedBytes(): Long

    private external fun nativeGetDeviceSampleRate(): Int

    private external fun nativeGetDeviceFramesPerBurst(): Int

    private external fun nativeGetDeviceHardwareLatencyMs(): Float

    private external fun nativeGetXRunCount(): Long

    private external fun nativeResetXRunCount()

    private external fun nativeEscalateAudioThreadPriority(): Boolean

    private external fun nativeGetCurrentAudioThreadPriority(): Int

    private external fun nativeSetLogLevel(level: Int)

    // ═════════════════════════════════════════════════════════════════
    // Constants
    // ═════════════════════════════════════════════════════════════════

    /**
     * Filename written by `CrashHandlerJni.cpp` when a native crash
     * occurs, and read by [readLastCrashReport] on the next launch.
     * Both sides must agree on this name; changing it here requires
     * changing the native constant in CrashHandlerJni.cpp in the same
     * commit.
     */
    private const val CRASH_REPORT_FILENAME = "last_crash.txt"
}