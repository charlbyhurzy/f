// futura-audio/engine/src/main/java/com/futura/engine/NativeUsbAudio.kt
//
// Phase 6 — USB Audio Class (UAC) direct driver JNI bridge.
//
// ⚠️  PATH NOTE — READ BEFORE EDITING  ⚠️
// The project tree (futura-audio.txt) places this file at
//     app/src/main/java/com/futura/engine/NativeUsbAudio.kt
// under Phase 6 — USB DAC, annotated "✅ JNI object". This delivery is
// at     engine/src/main/java/com/futura/engine/
// per an explicit instruction, matching the four other :engine-module
// JNI bridges delivered so far (NativeEngine, NativePlayerBridge,
// DspBridge, NativeUsbAudio). The Kotlin-side orchestrator —
// system/UsbAudioDirectDriver.kt per the tree — remains in :app and
// consumes this bridge through the existing :app → :engine dependency
// edge. See NativeEngine.kt's path note for the full consolidation
// story.
//
// ── Responsibility ──────────────────────────────────────────────────────
// Feature #141: "USB Audio Class (UAC) Direct Driver — Direct USB OTG
// controller bypassing Android OS mixer for external USB DACs."
//
// The tree's UsbAudioJni.cpp is annotated "✅ 3 USB methods". This file
// exposes exactly those three, and the surrounding Kotlin plumbing that
// makes them safe to call from the rest of the app:
//
//   1. attach(fd, config)  — hand a live USB file descriptor to the
//                            native side, which opens a UAC streaming
//                            interface with the requested format.
//   2. detach()            — release the UAC interface and stop any
//                            in-flight isochronous transfers.
//   3. getState()          — snapshot the current attachment state and
//                            the negotiated format the UAC link settled
//                            on.
//
// Configuration happens ONLY at attach time. This is deliberate: UAC
// audio format negotiation is a per-attachment operation — changing the
// sample rate or bit depth requires stopping the isochronous transfer
// stream, closing the streaming interface, and reopening it with new
// parameters. There is no meaningful "change format while attached"
// operation at the UAC protocol level, so exposing one would just be
// `detach() + attach()` under a misleading name. Callers who need to
// switch formats do exactly that — detach, then re-attach with a new
// [UsbAudioConfig].
//
// ── What this file does NOT do ──────────────────────────────────────────
// USB enumeration, permission requests, and endpoint binding are
// **caller responsibilities**, orchestrated through Android's
// `UsbManager` / `UsbDeviceConnection` APIs on the Kotlin side. This
// bridge does not import `android.hardware.usb.*` and does not attempt
// to open USB devices itself. The contract is:
//
//   Caller (system/UsbAudioDirectDriver.kt when delivered) does:
//     1. UsbManager.getDeviceList() → find a UAC-capable device
//     2. UsbManager.requestPermission(device, pendingIntent)
//     3. UsbManager.openDevice(device) → UsbDeviceConnection
//     4. connection.claimInterface(audioInterface, force = true)
//     5. connection.fileDescriptor → the Int passed to attach()
//     6. (Optionally) AudioManager.setCommunicationDevice() to route
//        the system audio path to the same device
//     7. Keep the UsbDeviceConnection alive for as long as the bridge
//        is attached (see "File descriptor ownership" below)
//     8. On teardown: detach(), then connection.close()
//
//   NativeUsbAudio (this file) does:
//     • Receive the already-open fd
//     • Wrap it with libusb via libusb_wrap_sys_device() (see
//       UsbDacPassthrough.h in the engine tree)
//     • Enumerate UAC streaming interfaces on the device
//     • Negotiate sample rate / bit depth / channels against the
//       requested UsbAudioConfig (or the device's defaults if 0)
//     • Open the isochronous endpoint for playback
//     • Report success/failure via attach()'s Boolean return
//     • Report negotiated parameters via getState()
//     • Tear down on detach() — without closing the caller's fd
//
// ── File descriptor ownership ──────────────────────────────────────────
// The file descriptor passed to attach() is **borrowed, not owned**.
// The native side wraps it, uses it for ioctls and transfers, and
// releases the wrapper on detach() — but does NOT call `close(fd)`.
// Closing the fd would invalidate the caller's UsbDeviceConnection and
// leave the Android USB subsystem in an inconsistent state.
//
// The Kotlin caller MUST:
//   • Keep the UsbDeviceConnection alive for the entire duration of the
//     attachment.
//   • Call detach() BEFORE closing the connection.
//   • Not close the connection while attached — this would cause
//     libusb to receive EBADF on the next transfer, which the native
//     side logs and reports as an error, but cannot cleanly recover
//     from.
//
// ── Practical scope (be aware) ─────────────────────────────────────────
// True bit-perfect USB DAC passthrough — the "bypass the Android mixer"
// promise of feature #141 — is constrained by Android's USB audio HAL.
// The system's `AudioService` typically claims the UAC interface before
// any user app is granted permission. Depending on the device and OS
// version, one of the following is achievable:
//
//   a) Routing through the system's UAC path via
//      AudioManager.setCommunicationDevice() with careful format
//      negotiation. The audio still passes through Android's USB audio
//      HAL, but if the source and DAC sample rates match, no resampling
//      occurs and playback is effectively bit-perfect (the HAL passes
//      the stream through untouched). This is the realistic path on
//      Android 10+.
//
//   b) Full exclusive control via libusb, requiring the caller to
//      successfully claim the interface with force=true. This works on
//      some older Android versions and on devices where the system
//      audio HAL does not preemptively claim UAC interfaces, but it
//      fails with EBUSY on most modern devices.
//
// This bridge supports BOTH paths: the fd it receives is valid either
// way, and the native side will attempt the libusb wrap and fall back
// to reporting "managed by system HAL" as the effective mode when the
// wrap fails with EBUSY. The `UsbAudioState.isBitPerfectActive` field
// reflects the effective mode, not the requested one — that is the
// field the UI must display for the Stream Inspector's bit-perfect
// badge.
//
// ── Design contract (identical to NativeEngine / NativePlayerBridge /
//    DspBridge / MlBridge) ────────────────────────────────────────────────
// Every external method is `private` and wrapped by a public method that:
//   1. Checks `libraryAvailable` — the .so may not exist yet.
//   2. Catches `UnsatisfiedLinkError` — thrown when an `external fun`
//      has no matching native implementation. Happens today because
//      engine/src/main/cpp/jni/UsbAudioJni.cpp has not been authored
//      yet.
//   3. Catches `Exception` — any other native failure.
//
// On failure, wrappers return their documented defaults:
//   • attach()    → false
//   • detach()    → no-op
//   • getState()  → null
//
// ── Threading contract ─────────────────────────────────────────────────
//   • attach() — blocking. Typical duration 20–200 ms while the native
//     side initialises libusb, enumerates UAC interfaces, and opens the
//     streaming endpoint. **Call off the main thread.**
//   • detach() — blocking. Typical duration 10–50 ms while isochronous
//     transfers are cancelled and the endpoint is closed. **Call off
//     the main thread.**
//   • getState() — cheap (single native call, reads a handful of
//     fields). Can be called from any thread.
//
// The natural caller (system/UsbAudioDirectDriver.kt, when delivered)
// runs on a dedicated audio-control thread, mirroring the pattern used
// by system/MediaSessionCoordinator.kt.
//
// ── JNI naming contract (for UsbAudioJni.cpp) ───────────────────────────
// This object is `object NativeUsbAudio` in package
// `com.futura.engine`. The three native functions must be named:
//
//     Java_com_futura_engine_NativeUsbAudio_nativeAttach(
//         JNIEnv* env, jobject thiz,
//         jint fd, jint preferredSampleRate, jint preferredBitDepth,
//         jint preferredChannels);
//
//     Java_com_futura_engine_NativeUsbAudio_nativeDetach(
//         JNIEnv* env, jobject thiz);
//
//     Java_com_futura_engine_NativeUsbAudio_nativeGetState(
//         JNIEnv* env, jobject thiz);
//
// `thiz` is a `jobject` (singleton instance), NOT a `jclass` — see
// NativeEngine.kt's JNI naming contract for the reasoning.
//
// ── Forward-reference audit ──────────────────────────────────────────
// Imported symbols:
//   • android.util.Log       (framework, all API levels)
//   • kotlin.jvm.Volatile    (Kotlin stdlib annotation)
//
// No `android.hardware.usb.*` imports — USB enumeration is the caller's
// job, as documented above. No project class, no Compose, no Hilt, no
// coroutines, no UsbAudioDirectDriver (not delivered), no data classes
// from :app. The two value objects below (UsbAudioConfig, UsbAudioState)
// and the UsbAudioError enum are authored in this file.
//
// Compiles and functions standalone against the entire delivered
// codebase. Zero crashes even before UsbAudioJni.cpp exists
// (libraryAvailable = false → every wrapper returns its documented
// default).

package com.futura.engine

import android.util.Log

// ═════════════════════════════════════════════════════════════════════════
// Configuration and state
// ═════════════════════════════════════════════════════════════════════════

/**
 * Requested UAC audio format for a USB DAC attachment.
 *
 * Every field defaults to `0`, which means "let the device pick its
 * default". This is the right default in most cases — devices know
 * their own preferred format better than any heuristic, and requesting
 * a mismatched format often fails silently with the device falling
 * back to something unexpected. Callers who know the target DAC's
 * capabilities can specify them explicitly to guarantee no resampling
 * occurs.
 *
 * Common combinations:
 *   • Hi-res DAC: `sampleRate = 96000, bitDepth = 24, channels = 2`
 *   • Standard DAC: `sampleRate = 48000, bitDepth = 16, channels = 2`
 *   • Bit-perfect passthrough: `sampleRate = 0` (source rate + device
 *     default negotiation will produce no resampling if they match)
 *
 * @param preferredSampleRate Requested sample rate in Hz. `0` means
 *        "device default". Non-zero values must match a rate the
 *        device supports, or `attach()` will fail with
 *        [UsbAudioError.UNSUPPORTED_FORMAT].
 * @param preferredBitDepth Requested bit depth (16, 24, or 32). `0`
 *        means "device default". Values outside the standard UAC set
 *        will fail with [UsbAudioError.UNSUPPORTED_FORMAT].
 * @param preferredChannels Requested channel count. `0` means "device
 *        default". Most portable DACs support 2 (stereo); a few
 *        high-end devices support 8 or more. Values exceeding the
 *        device's capabilities will fail with
 *        [UsbAudioError.UNSUPPORTED_FORMAT].
 */
data class UsbAudioConfig(
    val preferredSampleRate: Int = 0,
    val preferredBitDepth: Int = 0,
    val preferredChannels: Int = 0
)

/**
 * Snapshot of the current USB DAC attachment state.
 *
 * @param isAttached Whether the bridge currently holds an active UAC
 *        streaming interface. `false` means either the bridge is in
 *        its initial (detached) state, or attach() was called but
 *        failed.
 * @param negotiatedSampleRate The ACTUAL sample rate the UAC link
 *        settled on, in Hz. Differs from the requested rate when the
 *        device rejected the request and fell back to its default.
 *        Zero when not attached.
 * @param negotiatedBitDepth Actual negotiated bit depth. Zero when not
 *        attached.
 * @param negotiatedChannels Actual negotiated channel count. Zero when
 *        not attached.
 * @param isBitPerfectActive Whether bit-perfect playback is ACTUALLY
 *        occurring on this link — see the file header's "Practical
 *        scope" section. This is the field the Stream Inspector
 *        should display; it is NOT the same as "user requested
 *        bit-perfect" (that is a caller-side flag).
 * @param errorCode The last attach() failure, or [UsbAudioError.NONE]
 *        if the current state is healthy. Preserved across successful
 *        detach() calls so callers can inspect what caused a previous
 *        failure if attach-then-detach cycles happen faster than the
 *        UI can display the error.
 */
data class UsbAudioState(
    val isAttached: Boolean,
    val negotiatedSampleRate: Int,
    val negotiatedBitDepth: Int,
    val negotiatedChannels: Int,
    val isBitPerfectActive: Boolean,
    val errorCode: UsbAudioError
)

/**
 * Error codes for USB DAC attachment failures.
 *
 * Values are stable across releases — the native side and Kotlin side
 * both refer to them by integer, so reordering this enum requires a
 * coordinated change in UsbAudioJni.cpp.
 */
enum class UsbAudioError(val nativeValue: Int) {
    /** No error — either attached successfully or cleanly detached. */
    NONE(0),

    /** The file descriptor is not valid (closed, negative, or not a USB fd). */
    INVALID_FD(1),

    /** libusb initialisation failed — usually a missing or wrong-ABI .so. */
    LIBUSB_INIT_FAILED(2),

    /** Device is not a USB Audio Class device (no UAC interface found). */
    NOT_UAC_COMPLIANT(3),

    /** Requested format (sample rate / bit depth / channels) not supported. */
    UNSUPPORTED_FORMAT(4),

    /** USB interface claim failed — typically EBUSY when the system HAL owns it. */
    INTERFACE_CLAIM_FAILED(5),

    /** Streaming endpoint could not be opened. */
    STREAM_OPEN_FAILED(6);

    companion object {
        fun fromNative(value: Int): UsbAudioError =
            entries.firstOrNull { it.nativeValue == value } ?: NONE
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Bridge
// ═════════════════════════════════════════════════════════════════════════

/**
 * The 3-method JNI bridge to the native USB DAC driver.
 *
 * See the file header for the full design contract (safety-first
 * wrappers, fd ownership, threading, practical scope, JNI naming).
 */
object NativeUsbAudio {

    private const val TAG = "NativeUsbAudio"
    private const val LIBRARY_NAME = "futura_engine"

    /**
     * Number of integers returned by nativeGetState(). Matches the
     * layout documented in the native declarations section below.
     */
    private const val STATE_INT_COUNT = 6

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
                "Native library '$LIBRARY_NAME' unavailable — USB DAC driver disabled. " +
                    "Build :engine or check ABI filters if this is unexpected.",
                e
            )
            false
        }
    }

    /**
     * True if `libfutura_engine.so` loaded into the process. False means
     * USB DAC attachment is unavailable — playback through the system
     * audio path (normal Bluetooth/wired routing) still works.
     */
    fun isAvailable(): Boolean = libraryAvailable

    // ═════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════

    /**
     * Attach to an already-opened USB audio device using the given file
     * descriptor.
     *
     * Blocking (20–200 ms). **Call off the main thread.**
     *
     * **fd ownership**: [fd] is borrowed, not owned. The caller retains
     * ownership of the underlying `UsbDeviceConnection` and MUST keep it
     * alive for the entire duration of the attachment. The native side
     * will not close [fd]. See the file header's "File descriptor
     * ownership" section for the full lifecycle.
     *
     * @param fd The file descriptor obtained from
     *        `UsbDeviceConnection.getFileDescriptor()` on an
     *        already-opened USB audio device.
     * @param config Requested UAC audio format. See [UsbAudioConfig].
     *        Pass `UsbAudioConfig()` (all defaults) to let the device
     *        pick its preferred format.
     * @return true on success, false on any failure. The specific
     *         failure reason is available via [getState]'s `errorCode`
     *         field immediately after the failed call.
     */
    fun attach(fd: Int, config: UsbAudioConfig = UsbAudioConfig()): Boolean {
        val success = safeCall(false) {
            nativeAttach(
                fd = fd,
                preferredSampleRate = config.preferredSampleRate,
                preferredBitDepth = config.preferredBitDepth,
                preferredChannels = config.preferredChannels
            )
        }
        if (success) {
            Log.i(
                TAG,
                "USB DAC attached (requested: ${config.preferredSampleRate}Hz/" +
                    "${config.preferredBitDepth}bit/${config.preferredChannels}ch)"
            )
        } else {
            Log.w(TAG, "USB DAC attach failed")
        }
        return success
    }

    /**
     * Detach from the currently-attached USB audio device.
     *
     * Blocking (10–50 ms). **Call off the main thread.**
     *
     * Releases the UAC streaming interface and stops any in-flight
     * isochronous transfers, but does NOT close the caller's file
     * descriptor. The caller is responsible for closing the underlying
     * `UsbDeviceConnection` after this returns.
     *
     * Safe to call when not attached — no-op in that case.
     */
    fun detach() {
        safeCallUnit { nativeDetach() }
        Log.i(TAG, "USB DAC detached")
    }

    /**
     * Snapshot the current attachment state.
     *
     * @return The current [UsbAudioState], or `null` if the native
     *         library is unavailable or the returned state had an
     *         unexpected shape (logged).
     */
    fun getState(): UsbAudioState? {
        val raw = safeCall<IntArray?>(null) { nativeGetState() } ?: return null
        if (raw.size != STATE_INT_COUNT) {
            Log.w(
                TAG,
                "nativeGetState returned ${raw.size} ints, expected $STATE_INT_COUNT"
            )
            return null
        }
        return UsbAudioState(
            isAttached = raw[0] != 0,
            negotiatedSampleRate = raw[1],
            negotiatedBitDepth = raw[2],
            negotiatedChannels = raw[3],
            isBitPerfectActive = raw[4] != 0,
            errorCode = UsbAudioError.fromNative(raw[5])
        )
    }

    // ═════════════════════════════════════════════════════════════════
    // Safe-call helpers (identical pattern to the other :engine bridges)
    // ═════════════════════════════════════════════════════════════════

    private fun <T> safeCall(default: T, block: () -> T): T {
        if (!libraryAvailable) return default
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — UsbAudioJni.cpp likely not compiled", e)
            default
        } catch (e: Exception) {
            Log.e(TAG, "Native USB call failed", e)
            default
        }
    }

    private fun safeCallUnit(block: () -> Unit) {
        if (!libraryAvailable) return
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — UsbAudioJni.cpp likely not compiled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Native USB call failed", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Native declarations — 3 total
    // ═════════════════════════════════════════════════════════════════
    //
    // Private — callers cannot bypass the safe wrappers. JNI ignores
    // Kotlin visibility. `thiz` on the native side is a `jobject`
    // (singleton instance), NOT a `jclass`.

    private external fun nativeAttach(
        fd: Int,
        preferredSampleRate: Int,
        preferredBitDepth: Int,
        preferredChannels: Int
    ): Boolean

    private external fun nativeDetach()

    /**
     * Layout of the returned IntArray (length 6):
     *   [0] attached (0/1)
     *   [1] negotiated sample rate (Hz, 0 if not attached)
     *   [2] negotiated bit depth (0 if not attached)
     *   [3] negotiated channels (0 if not attached)
     *   [4] is bit-perfect active (0/1)
     *   [5] error code (see UsbAudioError.nativeValue)
     *
     * Both sides must agree on this layout. Changing it requires a
     * coordinated update here and in UsbAudioJni.cpp.
     */
    private external fun nativeGetState(): IntArray?
}