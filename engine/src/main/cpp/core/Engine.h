// futura-audio/engine/src/main/cpp/core/Engine.h
//
// Phase 3 — Native engine orchestrator (header).
//
// ── Dependency on AudioOutput.h ────────────────────────────────────────
// This header includes `audio/AudioOutput.h`, whose Oboe library types are
// explicitly qualified as `::oboe::`. `OboeStreamBuilder.h`, included
// transitively, uses the same qualification.
//
// ── Responsibility ──────────────────────────────────────────────────────
// Engine is the top-level coordinator of the entire native audio
// pipeline. Everything else under `engine/src/main/cpp/` is a
// component that Engine owns and orchestrates:
//
//     Engine
//       ├── AudioOutput            (audio/AudioOutput.h — DELIVERED)
//       │     └── OboeStreamBuilder (oboe/OboeStreamBuilder.h — DELIVERED)
//       ├── LockFreeRingBuffer     (core/LockFreeRingBuffer.h — future)
//       ├── StreamStateMachine     (core/StreamStateMachine.h — future)
//       ├── ZeroAllocRenderLoop    (core/ZeroAllocRenderLoop.h — future)
//       ├── FFmpegDecoderContext   (decode/FFmpegDecoderContext.h — future)
//       ├── FlacNativeDecoder      (decode/FlacNativeDecoder.h — future)
//       ├── ResamplerEngine        (decode/ResamplerEngine.h — future)
//       ├── DspChain               (dsp/DspChain.h — future)
//       └── GaplessPlaybackQueue   (core/GaplessPlaybackQueue.h — future)
//
// Engine's public API mirrors the Kotlin-side `NativePlayerBridge.kt`
// (delivered, 47 native methods), plus the device-info subset that
// `NativeEngine.kt` (delivered, 16 native methods) forwards. Every JNI
// method declared in those two Kotlin bridges ultimately calls a
// method on Engine (or a component Engine owns, like MemoryProfiler
// for the memory methods — those stay on NativeEngine directly, not
// through Engine).
//
// ── Pimpl rationale (why the header has almost no members) ─────────────
// Every component Engine owns lives in a header that hasn't been
// delivered yet. Including any of them here would be a forward
// reference — the project's build rule forbids it. Solution: the pimpl
// idiom.
//
//   • Engine.h declares `struct Impl;` (nested, self-contained) and
//     holds `std::unique_ptr<Impl> impl_`. No external header needed
//     for Impl's declaration.
//   • Engine.cpp defines `struct Engine::Impl { … }` at file scope,
//     where it CAN include LockFreeRingBuffer.h, StreamStateMachine.h,
//     etc. once those are delivered.
//   • Destructor is declared here and defined in Engine.cpp — required
//     because `std::unique_ptr<Impl>` needs Impl's complete type at
//     the point of destruction, which only exists in the .cpp.
//
// This keeps Engine.h self-contained (only depends on delivered
// headers + stdlib + oboe), Engine.h compiles standalone as soon as
// AudioOutput.h is fixed, and Engine.cpp becomes the only file that
// needs updating when new subsystems are delivered.
//
// ── Includes ────────────────────────────────────────────────────────────
//   • "audio/AudioOutput.h"  — DELIVERED (needs ::oboe:: fix first)
//   • <oboe/Oboe.h>          — declared dependency (.gitmodules)
//   • <atomic>               — stdlib (position tracking, play/pause state)
//   • <cstdint>              — stdlib (int32_t, int64_t)
//   • <memory>               — stdlib (unique_ptr for pimpl)
//   • <mutex>                — stdlib (guards error state)
//   • <string>               — stdlib (return types for format/error)
//
// Zero references to undelivered headers. Zero forward declarations
// outside the nested Impl.
//
// ── Threading contract ──────────────────────────────────────────────────
// Engine coordinates three threads:
//
//   Audio thread (Oboe) — calls onAudioReady() and onErrorAfterClose().
//     MUST be real-time-safe: no allocation, no mutex acquisition,
//     no blocking I/O. Every method the audio thread touches must
//     read atomics and pre-allocated buffers only.
//
//   Decoder thread — fills the ring buffer from the current source.
//     Reads the current position (atomic), writes to the ring buffer,
//     and reports back to the control thread through a lock-free
//     signal. Not present in this header as a public API — it's an
//     internal thread started by initialize().
//
//   Control thread — the caller's thread, invoking play/pause/seek/
//     volume/speed/pitch/etc. Must be serialized against itself
//     (Engine does not lock across control methods; the caller is
//     responsible for not calling control methods concurrently).
//     Typically MediaSessionCoordinator dispatches all of these on a
//     single dedicated "audio control" thread.
//
// Methods marked "safe from any thread" below are the exceptions that
// may be called from any of the three.
//
// ── Error handling philosophy ──────────────────────────────────────────
// Every method that can fail returns bool (or a nullable / sentinel
// value). Every failure populates `last_error_` (mutex-guarded) and
// `last_result_` (oboe::Result enum), retrievable via lastError() and
// lastResult(). This mirrors `AudioOutput.h`'s pattern and keeps a
// single error surface for the caller to inspect after any call.
//
// Method-level error handling does NOT attempt recovery. Recovery is a
// caller decision — e.g., on `onErrorAfterClose` (device disconnect),
// the caller's strategy might be "destroy this Engine, create a new
// one, resume at the last position". Engine does not have that policy
// built in.
//
// ── Forward-reference audit ────────────────────────────────────────────
// ✔ Delivered and included:
//     audio/AudioOutput.h    — AudioOutput, RenderCallback, AudioOutputConfig
//     <oboe/Oboe.h>          — Oboe library (declared dependency)
//     <atomic>, <cstdint>, <memory>, <mutex>, <string> — stdlib
// ✘ NOT included or referenced (all in tree, all future):
//     core/LockFreeRingBuffer.h
//     core/StreamStateMachine.h
//     core/ZeroAllocRenderLoop.h
//     core/GaplessPlaybackQueue.h
//     core/ThreadPriorityEscalator.h
//     core/NativeVolumeScaling.h
//     core/PcmChannelRemapper.h
//     core/FloatToInt16Converter.h
//     core/NativeMemoryPool.h
//     decode/FFmpegDecoderContext.h
//     decode/FlacNativeDecoder.h
//     decode/ResamplerEngine.h
//     decode/MemoryMappedFile.h
//     dsp/DspChain.h (and all 26 dsp/*.h files)
//     oboe/AAudioHardwarePath.h, OpenSLESFallback.h, AudioDeviceRoutingMonitor.h,
//          XRunDetector.h, DynamicBufferAutoTuner.h, HardwareLatencyCalculator.h,
//          ErrorCallbackHandler.h, BufferUnderrunRecovery.h
//     analysis/BpmCounter.h, ABLoopEngine.h, PitchSpeedPreservingEngine.h
//     bitperfect/BitPerfectSwitcher.h
//     usb/UsbDacPassthrough.h
//     profiler/MemoryLeakProfiler.h

#ifndef FUTURA_ENGINE_CORE_ENGINE_H
#define FUTURA_ENGINE_CORE_ENGINE_H

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include "audio/AudioOutput.h"

namespace futura::engine {

// ═════════════════════════════════════════════════════════════════════════
// Engine configuration
// ═════════════════════════════════════════════════════════════════════════

/**
 * Configuration for Engine initialization.
 *
 * The field set mirrors the Kotlin-side `NativeEngineConfig`
 * (`NativeEngine.kt`, delivered) — the Kotlin side packs these into
 * JNI parameters, and `JniBridge.cpp`'s `nativeInitialize` unpacks them
 * into this struct.
 *
 * Numeric fields use the same integer codes as the Kotlin side's enum
 * `nativeValue` accessors, so `JniBridge.cpp` can pass them through
 * without enum parsing:
 *
 *   performanceMode:
 *     0 = None
 *     1 = LowLatency  (Kotlin's default)
 *     2 = PowerSaving
 */
struct EngineConfig {

    /**
     * Requested sample rate in Hz. `0` = device native rate (bit-perfect
     * default). Non-zero values request a specific rate, which the
     * resampler (feature #59) will enforce if the source differs.
     */
    int32_t preferred_sample_rate = 0;

    /**
     * Requested buffer size in frames. `0` = auto-tune via
     * `oboe/DynamicBufferAutoTuner.h` (future). Non-zero values request
     * a specific buffer size.
     */
    int32_t preferred_frames_per_burst = 0;

    /**
     * Oboe performance mode. See the class-level doc comment for the
     * integer mapping. Default `1` (LowLatency) matches the Kotlin
     * `NativeEngineConfig.performanceMode` default.
     */
    int32_t performance_mode = 1;

    /**
     * Enable bit-perfect playback (feature #142). When true, the
     * resampler is bypassed and the stream requests the device's
     * native sample rate regardless of `preferred_sample_rate`. When
     * the effective output route cannot support bit-perfect (e.g.
     * Bluetooth), the stream falls back to normal mode and the state
     * is reflected via `isBitPerfectEnabled()` / the Stream Inspector.
     */
    bool enable_bit_perfect = false;

    /**
     * Enable xrun detection (feature #61). Negligible overhead (atomic
     * increment per xrun). Disable only for the tightest possible
     * audio path where even diagnostics cost is unacceptable.
     */
    bool enable_xrun_detection = true;

    /**
     * Absolute path to the directory where `CrashHandlerJni.cpp` writes
     * native crash reports. Created by the caller (typically
     * `context.getExternalFilesDir(null)`) before `initialize()` is
     * called. Empty string means "no crash handler installation" —
     * native crashes will not produce a report file.
     */
    std::string crash_log_dir;
};

// ═════════════════════════════════════════════════════════════════════════
// Engine
// ═════════════════════════════════════════════════════════════════════════

/**
 * Top-level native audio engine orchestrator.
 *
 * Owns the Oboe output stream (via [audio::AudioOutput]), the decoder
 * thread, the ring buffer, the DSP chain, and the playback state
 * machine. See the file header for the full responsibility graph, the
 * pimpl rationale, threading contract, and error handling philosophy.
 *
 * **Threading**: this class is designed to be driven from a single
 * control thread (see `system/MediaSessionCoordinator.kt` for the
 * Kotlin-side dispatcher). It communicates with the audio thread via
 * atomics and lock-free queues. The only exception is
 * `onAudioReady` and `onErrorAfterClose`, which are invoked by Oboe on
 * the audio thread and by contract must not block.
 *
 * **Error handling**: every fallible method returns `bool` (or a
 * sentinel-typed value) and populates `lastError()`/`lastResult()`.
 * Recovery policy is the caller's decision, not Engine's.
 */
class Engine : public audio::RenderCallback {
public:

    Engine();
    ~Engine() override;

    // Rule of five — Engine owns threads, mutexes, and a unique_ptr to
    // Impl. Copy/move are deleted; single-instance usage is the design.
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
    Engine(Engine&&) = delete;
    Engine& operator=(Engine&&) = delete;

    // ═════════════════════════════════════════════════════════════════
    // 1. Lifecycle
    // ═════════════════════════════════════════════════════════════════

    /**
     * Initialize the engine: open the output stream, start the decoder
     * thread, install the crash handler (if `config.crash_log_dir` is
     * set), and transition to Ready state.
     *
     * **Blocking** (50–200 ms). Call off the main thread.
     *
     * Idempotent in the "safe to call again" sense: if already
     * initialized, this tears down the previous state before applying
     * the new configuration. Callers who need to change configuration
     * can call `shutdown()` first and then `initialize()`.
     *
     * @return true on success; false on failure with `lastError()`
     *         populated. Failure modes: stream open failed (AudioOutput),
     *         decoder thread failed to start, or an inconsistent
     *         configuration.
     */
    bool initialize(const EngineConfig& config);

    /**
     * Shut down the engine and release all resources: stop the decoder
     * thread, close the output stream, destroy DSP chain and ring
     * buffer.
     *
     * **Blocking** (30–100 ms). Call off the main thread.
     *
     * Safe to call multiple times. After this returns, `isInitialized()`
     * is false until `initialize()` succeeds again.
     */
    void shutdown();

    /**
     * True if `initialize()` has been called successfully AND
     * `shutdown()` has not been called since.
     *
     * **Safe from any thread.**
     */
    bool isInitialized() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 2. Source preparation
    // ═════════════════════════════════════════════════════════════════

    /**
     * Prepare a source for playback. Opens the decoder context, reads
     * the file header to determine format/sample rate/channels/bit
     * depth, and pre-buffers enough audio to start playback without
     * underrun.
     *
     * **Blocking** (5–200 ms depending on format and storage speed).
     * Call off the main thread.
     *
     * @param uri Filesystem path, `content://` URI, or asset path.
     * @param source_type How to resolve the URI. Matches the Kotlin
     *        `SourceType.nativeValue`:
     *          0 = FILE
     *          1 = CONTENT_URI
     *          2 = ASSET
     *
     * @return true on success; false on failure (file not found,
     *         unsupported format, decoder error).
     */
    bool prepareSource(const std::string& uri, int source_type);

    /**
     * Reset to the IDLE state. Flushes the decoder, empties the queue,
     * and stops audio output, preserving the underlying `AudioOutput`
     * instance (no re-open). Equivalent to `stop() + clearQueue()`
     * combined but cheaper.
     *
     * **Blocking** (10–50 ms). Call off the main thread.
     */
    void reset();

    /**
     * True if a source is currently prepared and ready to play.
     *
     * **Safe from any thread.**
     */
    bool isPrepared() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 3. Transport
    // ═════════════════════════════════════════════════════════════════

    /**
     * Begin or resume playback. Requires a prepared source and audio
     * focus already granted by the caller (Engine does not request
     * audio focus itself — that is
     * `system/AudioFocusController.kt`'s responsibility on the Kotlin
     * side).
     *
     * @return true on success.
     */
    bool play();

    /**
     * Pause playback. Keeps the decoder warm so a subsequent `play()`
     * resumes instantly.
     *
     * @return true on success.
     */
    bool pause();

    /**
     * Stop playback and reset the playhead to position 0.
     *
     * @return true on success.
     */
    bool stop();

    /**
     * Seek to `position_ms`. Asynchronous — returns once the request is
     * queued. Actual playhead reaches the target within one render
     * frame (~10 ms). Values beyond duration are clamped by the caller
     * side (this method does not clamp).
     *
     * @return true if the seek was accepted; false if not prepared.
     */
    bool seekTo(int64_t position_ms);

    /**
     * True if playback is currently in progress.
     *
     * **Safe from any thread.**
     */
    bool isPlaying() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 4. Position / state
    // ═════════════════════════════════════════════════════════════════

    /**
     * Current playhead position in milliseconds.
     *
     * **Safe from any thread.**
     */
    int64_t getPositionMs() const noexcept;

    /**
     * Total duration of the current source in milliseconds, or `-1` if
     * duration is not known (live streams, corrupt headers).
     *
     * **Safe from any thread.**
     */
    int64_t getDurationMs() const noexcept;

    /**
     * Current high-level playback state as an integer matching
     * `NativePlaybackState.nativeValue` on the Kotlin side:
     *   1 = IDLE, 2 = READY, 3 = PLAYING, 4 = PAUSED, 5 = ENDED, 6 = ERROR
     *
     * **Safe from any thread.**
     */
    int getPlaybackState() const noexcept;

    /**
     * Current low-level stream state as an integer matching
     * `NativeStreamState.nativeValue` on the Kotlin side:
     *   0 = UNINITIALIZED, 1 = STREAM_OPENING, 2 = STREAM_STARTED,
     *   3 = STREAM_PAUSED, 4 = STREAM_FLUSHING, 5 = STREAM_STOPPED,
     *   6 = STREAM_CLOSING, 7 = STREAM_DISCONNECTED, 8 = STREAM_ERROR
     *
     * **Safe from any thread.**
     */
    int getStreamState() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 5. Volume
    // ═════════════════════════════════════════════════════════════════

    /**
     * Set output volume on a linear scale (0.0 silent, 1.0 unity
     * gain). Values outside `[0, 1]` are clamped. Applied by the render
     * loop before samples reach Oboe.
     *
     * **Safe from any thread** (writes an atomic).
     */
    void setVolumeLinear(float volume);

    /**
     * Set output volume in decibels (-96.0 to 0.0). Positive values are
     * clamped to 0 dB by the LoudnessMaximizer (feature #88) — this
     * method does not bypass it.
     *
     * **Safe from any thread** (writes an atomic; dB-to-linear
     * conversion happens on the audio thread).
     */
    void setVolumeDb(float db);

    /**
     * Current output volume on a linear scale.
     *
     * **Safe from any thread.**
     */
    float getVolumeLinear() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 6. Speed / pitch
    // ═════════════════════════════════════════════════════════════════

    /**
     * Set playback speed multiplier (0.5× to 3.0×). Speed changes do
     * not affect pitch (that is the pitch-preserving engine, feature
     * #100). Values outside the range are clamped.
     *
     * @return true if the engine is prepared and accepted the change.
     */
    bool setPlaybackSpeed(float speed);

    /**
     * Current playback speed multiplier (1.0 = normal).
     *
     * **Safe from any thread.**
     */
    float getPlaybackSpeed() const noexcept;

    /**
     * Set pitch offset in semitones (-24 to +24). Pitch changes do not
     * affect speed (that is the pitch-shifting engine, feature #69 —
     * SoundTouch phase vocoder). Values outside the range are clamped.
     *
     * @return true if the engine is prepared and accepted the change.
     */
    bool setPitchSemitones(float semitones);

    /**
     * Current pitch offset in semitones (0 = no shift).
     *
     * **Safe from any thread.**
     */
    float getPitchSemitones() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 7. A/B loop (feature #98)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Set the A and B loop points. Does not enable the loop —
     * `setLoopEnabled(true)` must also be called.
     *
     * @param start_ms A point (loop start) in milliseconds.
     * @param end_ms B point (loop end) in milliseconds. Must be greater
     *        than `start_ms` and less than duration.
     * @return true if parameters were valid; false otherwise.
     */
    bool setLoopAB(int64_t start_ms, int64_t end_ms);

    /**
     * Clear the A/B loop points and disable the loop.
     */
    void clearLoopAB();

    /**
     * Enable or disable the A/B loop. Requires valid A/B points to have
     * been set via `setLoopAB()`.
     */
    void setLoopEnabled(bool enabled);

    /**
     * True if the A/B loop is currently enabled AND has valid points.
     *
     * **Safe from any thread.**
     */
    bool isLoopEnabled() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 8. Gapless queue (feature #67)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enqueue the next track for gapless playback. Opens the next
     * file's decoder context in the background so the transition is
     * truly seamless. If a track is already enqueued, it is replaced.
     *
     * **Blocking** (5–200 ms for decoder context). Call off the main
     * thread.
     *
     * @return true on success; false on failure.
     */
    bool enqueueNext(const std::string& uri, int source_type);

    /**
     * Clear the gapless queue without affecting current playback.
     */
    void clearQueue();

    /**
     * Number of tracks currently enqueued (0 or 1).
     *
     * **Safe from any thread.**
     */
    int getQueueSize() const noexcept;

    /**
     * Skip to the next track. If a track is pre-decoded, the transition
     * is seamless; otherwise this returns false.
     *
     * @return true on success; false if no track is queued.
     */
    bool skipToNext();

    /**
     * True if the next track has been fully pre-decoded.
     *
     * **Safe from any thread.**
     */
    bool isNextPrepared() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 9. Source info
    // ═════════════════════════════════════════════════════════════════

    /**
     * Decoded source format string (e.g., `"FLAC"`, `"MP3"`, `"AAC"`).
     * Empty string when no source is loaded.
     *
     * **Safe from any thread** (returns a copy under a brief lock).
     */
    std::string getSourceFormat() const;

    /**
     * Decoded source sample rate in Hz. `0` when no source is loaded.
     *
     * **Safe from any thread.**
     */
    int32_t getSourceSampleRate() const noexcept;

    /**
     * Decoded source channel count. `0` when no source is loaded.
     *
     * **Safe from any thread.**
     */
    int32_t getSourceChannels() const noexcept;

    /**
     * Decoded source bit depth (16, 24, 32). `0` for lossy sources or
     * when no source is loaded.
     *
     * **Safe from any thread.**
     */
    int32_t getSourceBitDepth() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 10. Bit-perfect / resampler
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enable or disable bit-perfect playback (feature #142). When
     * enabled, the resampler is bypassed and the stream requests the
     * device's native sample rate.
     *
     * @return true if the current output route supports bit-perfect
     *         and the setting was applied; false otherwise (BT and
     *         some routes cannot deliver it).
     */
    bool setBitPerfectEnabled(bool enabled);

    /**
     * True if bit-perfect mode is enabled. Reflects the requested
     * setting, not the effective state (which depends on the output
     * route). Check `StreamInspector` for effective state when that
     * file is delivered.
     *
     * **Safe from any thread.**
     */
    bool isBitPerfectEnabled() const noexcept;

    /**
     * Set resampler quality preset. Matches Kotlin's
     * `ResamplerQuality.nativeValue`:
     *   0 = LOW, 1 = BALANCED, 2 = HIGH, 3 = BEST
     *
     * Takes effect on the next stream configuration.
     */
    void setResamplerQuality(int quality);

    /**
     * Current resampler quality preset (integer, see above).
     *
     * **Safe from any thread.**
     */
    int getResamplerQuality() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 11. Silence trimming (feature #99)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enable or disable silence trimming. When enabled, the decoder
     * scans for silence at the start and end of each track and skips
     * over it.
     */
    void setSilenceTrimEnabled(bool enabled);

    /**
     * True if silence trimming is enabled.
     *
     * **Safe from any thread.**
     */
    bool isSilenceTrimEnabled() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 12. Error state
    // ═════════════════════════════════════════════════════════════════

    /**
     * Human-readable description of the last failure. Empty after a
     * successful operation. **Safe from any thread.**
     */
    std::string getLastError() const;

    /**
     * Clear the last-error state. **Safe from any thread.**
     */
    void clearLastError();

    // ═════════════════════════════════════════════════════════════════
    // 13. Diagnostics
    // ═════════════════════════════════════════════════════════════════

    /**
     * Position up to which audio has been buffered, in milliseconds.
     * Always ≥ `getPositionMs()`.
     *
     * **Safe from any thread.**
     */
    int64_t getBufferedPositionMs() const noexcept;

    /**
     * Milliseconds of audio loaded into the decoder so far.
     *
     * **Safe from any thread.**
     */
    int64_t getLoadedMs() const noexcept;

    /**
     * Actual output sample rate in Hz (may differ from
     * `getSourceSampleRate()` when resampling).
     *
     * **Safe from any thread.**
     */
    int32_t getOutputSampleRate() const noexcept;

    /**
     * Actual output channel count.
     *
     * **Safe from any thread.**
     */
    int32_t getOutputChannels() const noexcept;

    /**
     * OS thread ID of the Oboe audio thread, or `-1` if the stream has
     * not started yet.
     *
     * **Safe from any thread.**
     */
    int32_t getRenderThreadId() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 14. Device info (mirrors NativeEngine.kt's device subset)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Actual sample rate negotiated with the audio device.
     *
     * **Safe from any thread.**
     */
    int32_t getDeviceSampleRate() const noexcept;

    /**
     * Actual buffer size in frames negotiated with the audio device.
     *
     * **Safe from any thread.**
     */
    int32_t getDeviceFramesPerBurst() const noexcept;

    /**
     * Round-trip hardware latency in milliseconds, as computed by
     * `oboe::AudioStream::calculateLatencyMillis()`.
     *
     * **Safe from any thread.**
     */
    double getDeviceHardwareLatencyMs() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // 15. RenderCallback (from audio::RenderCallback)
    // ═════════════════════════════════════════════════════════════════
    //
    // Overridden here because Engine passes itself to AudioOutput as
    // the render callback. The actual audio rendering is done by
    // Impl (which owns the ZeroAllocRenderLoop, once delivered) —
    // these forwarders just route the call.

    /**
     * Called by Oboe's audio thread. **Real-time** — must not block,
     * must not allocate, must not take locks. Forwards to Impl's
     * internal render loop.
     */
    ::oboe::DataCallbackResult onAudioReady(
        ::oboe::AudioStream* stream,
        void* audio_data,
        int32_t num_frames) override;

    /**
     * Called by Oboe's audio thread when the stream has been closed by
     * the OS (device disconnect, xrun recovery failure). **Real-time**
     * — same constraints as `onAudioReady`. Records the error and
     * forwards to any ErrorCallbackHandler the Impl may have (once
     * delivered).
     */
    void onErrorAfterClose(
        ::oboe::AudioStream* stream,
        ::oboe::Result error) override;

private:

    // ═════════════════════════════════════════════════════════════════
    // Pimpl
    // ═════════════════════════════════════════════════════════════════
    //
    // All state and all subsystem dependencies live inside Impl, which
    // is defined in Engine.cpp. This keeps Engine.h dependent only on
    // delivered headers, and lets Engine.cpp include the (currently
    // undelivered) headers for LockFreeRingBuffer, StreamStateMachine,
    // ZeroAllocRenderLoop, decoders, DspChain, etc.
    //
    // The unique_ptr destructor needs Impl's complete type at the
    // point of Engine::~Engine()'s instantiation — which is why the
    // destructor is declared here (not =default here) and defined in
    // Engine.cpp, where Impl is complete.

    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace futura::engine

#endif  // FUTURA_ENGINE_CORE_ENGINE_H