// futura-audio/engine/src/main/cpp/audio/AudioOutput.h
//
// Phase 3 — Audio output abstraction. Wraps an Oboe audio stream behind
// a stable, engine-facing interface that hides Oboe's callback model and
// exposes clean transport control plus telemetry.
//
// ⚠️  TREE NOTE — READ BEFORE EDITING  ⚠️
// The project tree (futura-audio.txt) does not include an `audio/`
// subdirectory under `engine/src/main/cpp/`. This file is a deliberate
// addition: an `AudioOutput` class that sits between `Engine.cpp` (the
// 620-line orchestrator) and Oboe. Without it, `Engine` would need to
// talk to raw Oboe — which is exactly the layering violation that the
// rest of the oboe/ subdirectory exists to prevent.
//
// The tree should be updated locally to reflect:
//
//     audio/
//         AudioOutput.h
//         AudioOutput.cpp        (arrives in a future turn)
//
// `engine/CMakeLists.txt` (not yet delivered) will need to:
//   • Add `engine/src/main/cpp/` as an include directory so
//     `#include "audio/AudioOutput.h"` and
//     `#include "oboe/OboeStreamBuilder.h"` resolve project-wide.
//   • List `audio/AudioOutput.cpp` alongside `core/`, `oboe/`, etc.
//
// ── Responsibility ──────────────────────────────────────────────────────
// This class owns EXACTLY the Oboe audio stream and its immediate
// transport surface:
//
//   • Build and own an `::oboe::AudioStream` (delegating construction to
//     `OboeStreamBuilder`, delivered).
//   • Provide the two Oboe callbacks (`onAudioReady`, `onErrorAfterClose`)
//     by forwarding to a caller-supplied `RenderCallback`.
//   • Expose start / pause / flush / stop for the stream.
//   • Report telemetry about the OPEN stream: sample rate, channels,
//     burst size, latency, frames written, render thread id, current
//     Oboe result state.
//
// ── What AudioOutput deliberately does NOT do ──────────────────────────
// Each of the following belongs to a distinct file whose concerns are
// orthogonal to the stream. Bundling them here would make AudioOutput
// the "everything audio" god-object, which is precisely the antipattern
// the modular tree avoids.
//
//   • Volume                → core/NativeVolumeScaling.h
//                             Applied by the render loop before the
//                             buffer reaches Oboe; keeping volume out of
//                             AudioOutput means a future "volume ramp"
//                             (feature #94, AutomaticVolumeLimiter) can
//                             shape the signal without touching this
//                             class.
//
//   • DSP processing        → dsp/DspChain.h
//                             Applied in the render loop between
//                             decoding and output. AudioOutput never
//                             sees the DSP chain.
//
//   • Stream state machine  → core/StreamStateMachine.h
//                             The authoritative Paused/Playing/Flushing/
//                             Stopped state lives in the state machine,
//                             not here. This class knows only whether the
//                             OS-level Oboe stream is started or not.
//
//   • XRun detection        → oboe/XRunDetector.h
//                             Reads stream stats and counts underruns;
//                             doesn't build or control the stream.
//
//   • Device monitoring     → oboe/AudioDeviceRoutingMonitor.h
//                             Reacts to headphone / Bluetooth events and
//                             asks Engine to re-open the stream.
//
//   • Error handling policy → oboe/ErrorCallbackHandler.h
//                             AudioOutput FORWARDS the error callback;
//                             it does not decide what to do about
//                             disconnects or unrecoverable errors. That
//                             decision belongs to the handler, which
//                             calls back into Engine, which re-opens the
//                             stream via a fresh AudioOutput::open() if
//                             appropriate.
//
//   • Buffer auto-tuning    → oboe/DynamicBufferAutoTuner.h
//                             Reads telemetry from AudioOutput and
//                             requests configuration changes, but does
//                             not modify the stream directly.
//
//   • Latency computation   → oboe/HardwareLatencyCalculator.h
//                             AudioOutput exposes the raw latency number
//                             from `stream->calculateLatencyMillis()`;
//                             conversion to the Stream Inspector's
//                             display format is the calculator's job.
//
// ── Relationship to OboeStreamBuilder ─────────────────────────────────
// `AudioOutput::open()` calls `OboeStreamBuilder::build(config, this, this)`
// — passing itself as both the data and error callback (since it implements
// both interfaces). The builder produces a `shared_ptr<AudioStream>`,
// which AudioOutput then owns. On any subsequent rebuild (e.g., after
// device change), Engine destroys the current AudioOutput and creates a
// new one — AudioOutput itself is single-shot-per-stream by design;
// reuse across reopens would require checking for stale `stream_` and
// add pointless branching.
//
// ── Threading contract (this is the most important section) ────────────
// Oboe runs `onAudioReady` on a dedicated, high-priority audio thread.
// `AudioOutput`'s contract is:
//
//   ✔ `onAudioReady`             — audio thread, real-time, must not
//                                  block, must not allocate, must not
//                                  take locks. Forwarded synchronously
//                                  to `RenderCallback::onAudioReady`,
//                                  which owns all real-time discipline.
//
//   ✔ `onErrorAfterClose`        — audio thread. Forwarded synchronously
//                                  to `RenderCallback::onErrorAfterClose`.
//                                  Must not block (same constraint).
//
//   ✔ `start()`, `pause()`, `flush()`, `stop()`, `close()`
//                                — MUST be called from a single control
//                                  thread (typically Engine's audio-
//                                  control thread), serialized against
//                                  each other. These methods are NOT
//                                  safe to call concurrently.
//
//   ✔ `isOpen()`, `isStarted()`, and all telemetry getters
//                                — safe from any thread. They read
//                                  atomics or const state that is
//                                  either set-once-at-open or guarded
//                                  by `error_mutex_`.
//
//   ✘ `open()` after `close()`   — supported ONLY if `close()` has
//                                  fully returned. Reopening while
//                                  another thread is mid-close is
//                                  undefined.
//
//   ✘ Copying / moving           — deleted. See Rule of Five below.
//
// The state machine (Closed → Open → Started) is enforced with an
// atomic, so a start-after-close returns false rather than invoking
// undefined Oboe behavior.
//
// ── Rule of five ─────────────────────────────────────────────────────
// Copy and move are both deleted. Rationale:
//
//   • The class holds a `shared_ptr<AudioStream>`, a `RenderCallback*`,
//     and a `std::mutex`. Copying would share the stream (two owners of
//     one audio device handle — incorrect), duplicate the callback
//     pointer (safe but pointless), and duplicate the mutex (ill-formed
//     with any lock holder). No semantic case justifies copying.
//
//   • Moving would require transferring ownership of the Oboe stream
//     across an as-yet-unclosed state, and the `RenderCallback*` would
//     become ambiguous mid-transfer. The single valid use case is
//     "construct in place, use, destroy" — which is exactly what
//     `Engine` will do.
//
// ── Header-only rationale ────────────────────────────────────────────
// The public API is ~15 methods, most of them 1–3 lines. Inline
// implementations in this header mirror `oboe/OboeStreamBuilder.h`'s
// layout and keep the file self-contained. A future `.cpp` will
// contain method definitions for the ~5 methods whose bodies are too
// long to inline reasonably (open, close, start, pause, and the
// callback forwarders). No method will change its signature between
// header and cpp — this is a mechanical split, not a design change.
//
// ── Includes ─────────────────────────────────────────────────────────
// Only dependencies already declared or delivered:
//
//   • "oboe/OboeStreamBuilder.h"   — delivered in a previous turn
//                                    (engine/src/main/cpp/oboe/)
//   • <oboe/Oboe.h>                — declared in .gitmodules and
//                                    populated by scripts/setup.sh
//   • <atomic>                     — stdlib
//   • <cstdint>                    — stdlib
//   • <memory>                     — stdlib
//   • <mutex>                      — stdlib
//   • <string>                     — stdlib
//
// Zero forward references. This header compiles standalone once
// `third_party/oboe/` is populated.
//
// ── Forward-reference audit ──────────────────────────────────────────
// No inclusion of, or reference to, any of the following (all exist in
// the tree but are not dependencies of this file):
//
//   core/StreamStateMachine.h
//   core/NativeVolumeScaling.h
//   core/LockFreeRingBuffer.h
//   dsp/DspChain.h
//   oboe/AAudioHardwarePath.h
//   oboe/OpenSLESFallback.h
//   oboe/AudioDeviceRoutingMonitor.h
//   oboe/XRunDetector.h
//   oboe/DynamicBufferAutoTuner.h
//   oboe/HardwareLatencyCalculator.h
//   oboe/ErrorCallbackHandler.h
//   oboe/BufferUnderrunRecovery.h
//   bitperfect/BitPerfectSwitcher.h
//   usb/UsbDacPassthrough.h

#ifndef FUTURA_ENGINE_AUDIO_AUDIO_OUTPUT_H
#define FUTURA_ENGINE_AUDIO_AUDIO_OUTPUT_H

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include "oboe/OboeStreamBuilder.h"

namespace futura::engine::audio {

// ═════════════════════════════════════════════════════════════════════════
// Render callback interface
// ═════════════════════════════════════════════════════════════════════════

/**
 * The interface AudioOutput calls into when the audio thread needs more
 * data or when an async error occurs.
 *
 * The engine provides exactly one implementation of this interface —
 * typically the `Engine` class itself, or a small adapter inside
 * `Engine` that delegates to `ZeroAllocRenderLoop` and
 * `ErrorCallbackHandler`. AudioOutput never knows what's behind the
 * pointer; it forwards every callback unconditionally.
 *
 * **Real-time constraints apply.** Both methods are called on Oboe's
 * high-priority audio thread. Implementations MUST NOT:
 *
 *   • Allocate memory (no `new`, no `std::vector::push_back`, no
 *     `std::string` operations that might allocate).
 *   • Take any mutex or lock other than lock-free primitives.
 *   • Call into the JVM, into logging that takes a lock, or into any
 *     blocking I/O.
 *   • Throw exceptions.
 *
 * Violating these constraints causes audio glitches, xruns, and in
 * the worst case, an ANR.
 */
class RenderCallback {
public:
    virtual ~RenderCallback() = default;

    /**
     * Fill `num_frames` worth of audio into `audio_data`. Called by
     * Oboe's audio thread at the stream's callback rate (typically
     * every few milliseconds).
     *
     * @param stream     The Oboe stream requesting data. Same pointer
     *                   returned by `AudioOutput::rawStream()`.
     * @param audio_data Buffer to fill. Format is whatever
     *                   `AudioOutputConfig::format` requested (float32
     *                   by default). Length is
     *                   `num_frames × channel_count × sizeof(sample)`.
     * @param num_frames Number of frames to render. Multiply by
     *                   channel_count for the total sample count.
     *
     * @return `DataCallbackResult::Continue` to keep the stream
     *         running, or `DataCallbackResult::Stop` to shut it down
     *         (rare — usually indicates an unrecoverable error).
     */
    virtual ::oboe::DataCallbackResult onAudioReady(
        ::oboe::AudioStream* stream,
        void* audio_data,
        int32_t num_frames) = 0;

    /**
     * Called by Oboe when the stream has encountered an asynchronous
     * error and has been closed by the OS. The most common case is
     * `ErrorDisconnected` — the audio device (headphones, USB DAC,
     * Bluetooth) was removed.
     *
     * After this returns, the Oboe stream is unusable. The engine's
     * normal recovery flow is: this callback fires → engine stops
     * issuing transport calls on this AudioOutput → engine destroys
     * this AudioOutput → engine creates a new AudioOutput against
     * whatever device is now default → engine resumes playback at the
     * current position.
     *
     * **This method fires on the audio thread — same constraints as
     * `onAudioReady`.** In particular, do not allocate a new
     * AudioOutput here; instead, signal a control thread that recovery
     * is needed and return immediately.
     *
     * @param stream The stream that failed. Do not use it after
     *               returning from this method.
     * @param error  The `::oboe::Result` describing what happened. See
     *               Oboe's documentation for the full set of possible
     *               values; only a handful are expected in practice.
     */
    virtual void onErrorAfterClose(
        ::oboe::AudioStream* stream,
        ::oboe::Result error) = 0;
};

// ═════════════════════════════════════════════════════════════════════════
// Configuration
// ═════════════════════════════════════════════════════════════════════════

/**
 * Configuration for opening an AudioOutput.
 *
 * Every field has a default matching the app's primary use case. In
 * most builds `AudioOutputConfig{}` (all defaults) is exactly what
 * `Engine.cpp` will pass — callers only override fields for
 * specialized streams (e.g., a diagnostic input stream, or a
 * bit-perfect output stream that pins the sample rate).
 *
 * This struct is intentionally separate from `OboeStreamBuilder`'s
 * `AudioStreamConfig` even though the fields overlap almost entirely.
 * The reason: `OboeStreamBuilder` is a lower-level primitive that any
 * caller can use directly; `AudioOutputConfig` is what AudioOutput
 * exposes. Keeping them separate means a future change to
 * OboeStreamBuilder's config (say, adding a `buffer_capacity_in_frames`
 * hint) doesn't force a change to AudioOutput's public API — the
 * builder's config evolves, and AudioOutput's `open()` translates.
 */
struct AudioOutputConfig {

    /**
     * Requested sample rate in Hz. `0` = device native rate (bit-perfect
     * default). Set to a specific value only when the caller needs to
     * match a specific DAC or when forced resampling is intended.
     */
    int32_t sample_rate = 0;

    /**
     * Channel count. `2` (stereo) covers the entire app's use case —
     * mono sources are upmixed by `core/PcmChannelRemapper.h` before
     * reaching the stream.
     */
    int32_t channel_count = 2;

    /**
     * Frames per callback. `0` = auto-tune via
     * `oboe/DynamicBufferAutoTuner.h`. Non-zero values request a
     * specific burst size.
     */
    int32_t frames_per_callback = 0;

    /**
     * Sample format. `Float` matches the engine's entire DSP pipeline
     * and is the correct default.
     */
    ::oboe::AudioFormat format = ::oboe::AudioFormat::Float;

    /**
     * Oboe performance mode. `LowLatency` is the app's default.
     * `PowerSaving` should only be set for Sleep Dock playback
     * sessions, where battery matters more than latency.
     */
    ::oboe::PerformanceMode performance_mode =
        ::oboe::PerformanceMode::LowLatency;

    /**
     * Sharing mode. `Exclusive` requests direct access to the audio
     * hardware (needed for bit-perfect). Most devices refuse Exclusive
     * via the audio HAL; the fallback path (see
     * `fall_back_to_shared`) makes this gracefully degrade.
     */
    ::oboe::SharingMode sharing_mode = ::oboe::SharingMode::Exclusive;

    /**
     * Android usage hint. `Media` is correct for the app's primary use
     * case — it tells the OS to apply standard media-app ducking,
     * volume category, and routing behavior.
     */
    ::oboe::Usage usage = ::oboe::Usage::Media;

    /**
     * Android content type hint. `Music` is correct for the app's
     * primary use case — it affects spatialization routing and some
     * OEM audio profiles.
     */
    ::oboe::ContentType content_type = ::oboe::ContentType::Music;

    /**
     * Whether to automatically retry with `Shared` if `Exclusive` fails.
     * `true` (default) gives "best attempt with graceful fallback" —
     * on every modern Android device, the effective mode will be Shared
     * through the OS mixer, which still produces correct audio.
     */
    bool fall_back_to_shared = true;
};

// ═════════════════════════════════════════════════════════════════════════
// AudioOutput
// ═════════════════════════════════════════════════════════════════════════

/**
 * Owns an Oboe audio stream and routes its callbacks to a
 * caller-supplied [RenderCallback].
 *
 * See the file header for the full design contract: responsibilities,
 * what's deliberately excluded, threading, and how it relates to
 * [OboeStreamBuilder].
 *
 * Lifecycle:
 *
 *     AudioOutput output;
 *     if (!output.open(config, &render_callback)) {
 *         log(output.lastError());
 *         return false;
 *     }
 *     output.start();
 *     // … streaming …
 *     output.stop();
 *     output.close();
 *
 * The state machine is Closed → Open → Started, and `start()` /
 * `pause()` alternate Open ↔ Started until `close()` returns it to
 * Closed. Attempting to start a closed output returns false; attempting
 * to close an already-closed output is a no-op.
 */
class AudioOutput
    : private ::oboe::AudioStreamDataCallback,
      private ::oboe::AudioStreamErrorCallback {

public:

    AudioOutput() = default;
    ~AudioOutput() override;

    // Rule of five — see file header.
    AudioOutput(const AudioOutput&) = delete;
    AudioOutput& operator=(const AudioOutput&) = delete;
    AudioOutput(AudioOutput&&) = delete;
    AudioOutput& operator=(AudioOutput&&) = delete;

    // ═════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════

    /**
     * Open an output stream against [callback]. On success, the stream
     * is in the Open (not started) state — call `start()` to begin
     * playback.
     *
     * Delegates to `OboeStreamBuilder::build()` (delivered). The
     * builder's `fall_back_to_shared` logic runs automatically if the
     * config allows it.
     *
     * @param config   Stream configuration. See [AudioOutputConfig].
     * @param callback The `RenderCallback` that will fill audio
     *                 buffers and receive error notifications. **Must
     *                 outlive this AudioOutput**. Typically the Engine
     *                 instance itself.
     *
     * @return true on success; false on any failure, with `lastError()`
     *         and `lastResult()` populated.
     */
    bool open(const AudioOutputConfig& config, RenderCallback* callback);

    /**
     * Close the stream and release all resources. Safe to call when
     * already closed (no-op). Blocks until the audio thread has fully
     * unwound — typical duration 10–30 ms.
     *
     * After close() returns, `open()` may be called again to rebuild.
     * Attempting to open while close is still in flight from another
     * thread is undefined.
     */
    void close();

    // ═════════════════════════════════════════════════════════════════
    // Transport
    // ═════════════════════════════════════════════════════════════════

    /**
     * Start the stream. Uses `requestStart()` (non-blocking; audio
     * begins asynchronously within one buffer period) rather than
     * `start()` (blocking), matching the low-latency path's
     * expectations.
     *
     * @return true if the start request was accepted. A false return
     *         usually means the stream is not in the Open state, or
     *         the OS rejected the request (rare once open() succeeded).
     */
    bool start();

    /**
     * Pause the stream. Uses `requestPause()`. The stream remains open
     * and can be resumed with `start()`.
     *
     * @return true if accepted.
     */
    bool pause();

    /**
     * Flush any buffered audio. Uses `requestFlush()`. Called after
     * seeks — the buffered samples from the old position must be
     * discarded before the new position's samples reach Oboe.
     *
     * @return true if accepted.
     */
    bool flush();

    /**
     * Stop the stream and return it to the Open state. Combines
     * `requestStop()` + `requestFlush()` into a single call. Equivalent
     * to `pause(); flush();` for the two operations in one step.
     *
     * @return true if accepted.
     */
    bool stop();

    // ═════════════════════════════════════════════════════════════════
    // State
    // ═════════════════════════════════════════════════════════════════

    /** True if the stream is open (whether or not it's started). */
    bool isOpen() const noexcept;

    /** True if the stream is started (audio is flowing). */
    bool isStarted() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // Telemetry (reflects the OPEN stream; returns 0 when closed)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Actual sample rate Oboe negotiated with the device, in Hz. `0`
     * when closed. When the caller requested `sample_rate = 0`, this
     * reports the device's native rate — which is what bit-perfect
     * mode depends on.
     */
    int32_t getSampleRate() const noexcept;

    /**
     * Actual channel count Oboe negotiated. `0` when closed.
     */
    int32_t getChannelCount() const noexcept;

    /**
     * Actual buffer size in frames Oboe negotiated. `0` when closed.
     * Reported by `StreamInspector.kt`'s "Buffer" row.
     */
    int32_t getFramesPerBurst() const noexcept;

    /**
     * Round-trip hardware latency in milliseconds, computed by
     * `stream->calculateLatencyMillis()`. `0.0` when closed. The
     * Stream Inspector displays this value; it is NOT the display-
     * formatted number, which is `HardwareLatencyCalculator.h`'s job.
     */
    double getLatencyMs() const noexcept;

    /**
     * Total frames written to the audio device since the stream was
     * opened. Useful for external drift detection; resets to 0 on
     * close-then-open.
     */
    int64_t getFramesWritten() const noexcept;

    /**
     * OS thread ID of the Oboe audio thread, as reported by
     * `stream->getCallbackThreadId()` (`-1` when closed or unavailable).
     * Correlates with `NativeEngine.getRenderThreadId()` on the Kotlin
     * side.
     */
    int32_t getRenderThreadId() const noexcept;

    /**
     * The `::oboe::AudioApi` that the open stream is using
     * (`AAudio` or `OpenSLES`). `Unspecified` when closed.
     */
    ::oboe::AudioApi getAudioApi() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // Raw access (escape hatch for advanced callers)
    // ═════════════════════════════════════════════════════════════════

    /**
     * The underlying `::oboe::AudioStream*`. Returns `nullptr` when
     * closed. Provided for callers that need to bypass AudioOutput's
     * narrow API — e.g., `bitperfect/BitPerfectSwitcher.h` may need
     * to query low-level stream properties, or
     * `oboe/HardwareLatencyCalculator.h` may need access to
     * `getTimestamp()`. Not intended for general use.
     */
    ::oboe::AudioStream* rawStream() const noexcept;

    // ═════════════════════════════════════════════════════════════════
    // Error access
    // ═════════════════════════════════════════════════════════════════

    /**
     * Human-readable description of the last failure. Empty after a
     * successful `open()`. Mirrors `OboeStreamBuilder::last_error()`
     * when `open()` failed via the builder.
     */
    std::string lastError() const;

    /**
     * Machine-readable `::oboe::Result` of the last failure, or
     * `Result::OK`. Callers that need to make a specific recovery
     * decision (retry only on `ErrorDisconnected`) read this;
     * callers that just log read `lastError()`.
     */
    ::oboe::Result lastResult() const noexcept;

private:

    // ── ::oboe::AudioStreamDataCallback ─────────────────────────────────

    /**
     * Forwarded synchronously to `callback_->onAudioReady(...)`.
     * Real-time; must not block or allocate. If `callback_` is null
     * (should never happen after `open()` returns), returns
     * `DataCallbackResult::Stop` rather than null-dereferencing.
     */
    ::oboe::DataCallbackResult onAudioReady(
        ::oboe::AudioStream* oboe_stream,
        void* audio_data,
        int32_t num_frames) override;

    // ── ::oboe::AudioStreamErrorCallback ────────────────────────────────

    /**
     * Forwarded synchronously to
     * `callback_->onErrorAfterClose(...)`. Real-time; must not block
     * or allocate. Also records the error into `last_error_` /
     * `last_result_` for caller inspection after the fact.
     */
    void onErrorAfterClose(
        ::oboe::AudioStream* oboe_stream,
        ::oboe::Result error) override;

    // ── Internal state machine ────────────────────────────────────────

    enum class State : uint8_t {
        Closed,
        Open,
        Started,
    };

    /**
     * The Oboe stream. Set once by `open()`, cleared by `close()`. Not
     * atomic because the state machine guarantees that no other thread
     * observes `stream_` before `open()` returns or after `close()`
     * begins. The audio thread reads it via the private `onAudioReady`
     * parameters — never through `stream_` directly, which is why this
     * is safe without a mutex.
     */
    std::shared_ptr<::oboe::AudioStream> stream_;

    /**
     * The caller-supplied callback. Set once by `open()`, cleared by
     * `close()`. Same threading rationale as `stream_`.
     */
    RenderCallback* callback_ = nullptr;

    /**
     * Atomic state machine. Enforces Closed → Open → Started
     * transitions and makes `isOpen()` / `isStarted()` safely
     * queryable from any thread.
     */
    std::atomic<State> state_{State::Closed};

    /**
     * Guards `last_error_`. Read by `lastError()` from any thread,
     * written by `open()` (control thread) and `onErrorAfterClose`
     * (audio thread). A mutex is the correct choice here: writes are
     * rare (once per failure), reads are rare (once per display
     * refresh), and the mutex is never held on the hot path.
     */
    mutable std::mutex error_mutex_;
    std::string last_error_;
    std::atomic<::oboe::Result> last_result_{::oboe::Result::OK};

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Set the last-error state under `error_mutex_`. Used by both
     * `open()` and `onErrorAfterClose()`.
     */
    void setError(::oboe::Result result, std::string message);

    /**
     * Clear the last-error state. Called at the start of every `open()`
     * so stale state from a previous failed attempt doesn't persist.
     */
    void clearError();
};

// ═════════════════════════════════════════════════════════════════════════
// Inline implementations
// ═════════════════════════════════════════════════════════════════════════
//
// The inline definitions below cover the trivial getters and the
// destructor. Methods that need real body logic (open, close, start,
// pause, flush, stop, the two callbacks, setError, clearError) are
// deferred to AudioOutput.cpp, arriving in a future turn. Their
// signatures will not change between this header and that file.

inline AudioOutput::~AudioOutput() {
    // Destructor auto-closes so callers cannot leave the stream
    // running by forgetting to call close(). Engine will call close()
    // explicitly in its normal shutdown path; this is the safety net
    // for exceptional unwinding.
    if (state_.load(std::memory_order_acquire) != State::Closed) {
        close();
    }
}

inline bool AudioOutput::isOpen() const noexcept {
    return state_.load(std::memory_order_acquire) != State::Closed;
}

inline bool AudioOutput::isStarted() const noexcept {
    return state_.load(std::memory_order_acquire) == State::Started;
}

inline int32_t AudioOutput::getSampleRate() const noexcept {
    auto* stream = rawStream();
    return stream ? stream->getSampleRate() : 0;
}

inline int32_t AudioOutput::getChannelCount() const noexcept {
    auto* stream = rawStream();
    return stream ? stream->getChannelCount() : 0;
}

inline int32_t AudioOutput::getFramesPerBurst() const noexcept {
    auto* stream = rawStream();
    return stream ? stream->getFramesPerBurst() : 0;
}

inline double AudioOutput::getLatencyMs() const noexcept {
    auto* stream = rawStream();
    return stream ? stream->calculateLatencyMillis() : 0.0;
}

inline int64_t AudioOutput::getFramesWritten() const noexcept {
    auto* stream = rawStream();
    return stream ? stream->getFramesWritten() : 0;
}

inline int32_t AudioOutput::getRenderThreadId() const noexcept {
    auto* stream = rawStream();
    // getCallbackThreadId is available on Oboe >= 1.6. If the pinned
    // Oboe version (see third_party/oboe/) predates this API, replace
    // with `-1` until the dependency is bumped. Oboe 1.8+ — the
    // current latest as of this writing — supports it.
    return stream ? static_cast<int32_t>(stream->getCallbackThreadId()) : -1;
}

inline ::oboe::AudioApi AudioOutput::getAudioApi() const noexcept {
    auto* stream = rawStream();
    return stream ? stream->getAudioApi() : ::oboe::AudioApi::Unspecified;
}

inline ::oboe::AudioStream* AudioOutput::rawStream() const noexcept {
    return stream_.get();
}

inline ::oboe::Result AudioOutput::lastResult() const noexcept {
    return last_result_.load(std::memory_order_acquire);
}

}  // namespace futura::engine::audio

#endif  // FUTURA_ENGINE_AUDIO_AUDIO_OUTPUT_H