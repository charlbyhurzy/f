// futura-audio/engine/src/main/cpp/oboe/OboeStreamBuilder.h
//
// Phase 3 — Oboe stream builder. Feature #51 in the project catalogue:
//
//     "Oboe Stream Builder: Native C++ Oboe initialization requesting
//      PerformanceMode::LowLatency."
//
// ── Responsibility ──────────────────────────────────────────────────────
// This header owns exactly one concern: given a configuration, open an
// `::oboe::AudioStream` and return it. Everything else about the audio
// path — how the stream is fed (render loop, ring buffer), how it
// reacts to device changes (AudioDeviceRoutingMonitor.h), how xruns are
// detected (XRunDetector.h), how buffer size is auto-tuned
// (DynamicBufferAutoTuner.h), how errors are handled
// (ErrorCallbackHandler.h) — lives in the sibling headers under oboe/.
//
// That narrow scope matters because:
//
//   • `Engine.cpp` (620 lines, the module's orchestrator) will call
//     `OboeStreamBuilder` exactly once, at startup, to open the primary
//     output stream.
//   • `AAudioHardwarePath.h` (feature #52) and `OpenSLESFallback.h`
//     (feature #53) will call `build_with_api()` with explicit
//     `::oboe::AudioApi::AAudio` / `::oboe::AudioApi::OpenSLES` when they
//     need to force a specific hardware path.
//   • `BufferUnderrunRecovery.h` (feature #153) will call `build()`
//     again to re-open the stream after a device disconnect.
//
// All three callers need exactly the same primitive: "build me a
// stream with this config; tell me what happened." This header is that
// primitive and nothing more.
//
// ── Why header-only ──────────────────────────────────────────────────
// The project tree (futura-audio.txt) lists only `.h` files under
// `engine/src/main/cpp/oboe/`. There is no `OboeStreamBuilder.cpp`.
// This is consistent with the tree's convention of listing `.cpp` only
// for substantial implementations (`Engine.cpp` at 620 lines,
// `FlacFrameScanner.cpp`, and the seven `jni/*.cpp` bridges). A
// stream-builder class of ~90 lines with all logic being Oboe API
// calls is correctly header-only — splitting it into .h/.cpp would add
// build churn for zero benefit.
//
// ── What it includes ─────────────────────────────────────────────────
// `<oboe/Oboe.h>` — the Oboe third-party dependency, declared in
// `.gitmodules` (`engine/src/main/cpp/third_party/oboe/`), populated by
// `scripts/setup.sh` (per the README delivered earlier), and linked by
// `CMakeLists.txt` (not yet authored, but the include path is
// pre-declared in `engine/build.gradle.kts` via the NDK's CMake
// integration). Including it here is a valid forward reference to a
// declared dependency.
//
// Standard library: `<cstdint>` (int32_t), `<memory>` (shared_ptr),
// `<string>` (last_error_). All C++17-compatible; the module compiles
// with `-std=c++17` per `engine/build.gradle.kts`.
//
// ── What it deliberately does NOT include ────────────────────────────
// No `#include "../core/StreamStateMachine.h"` — state tracking is the
// render loop's job, not the builder's.
// No `#include "AudioDeviceRoutingMonitor.h"` — device monitoring
// observes the stream, it doesn't build it.
// No `#include "XRunDetector.h"` — xrun detection reads stream stats,
// it doesn't build the stream.
// No `#include "DynamicBufferAutoTuner.h"` — tuning adjusts an
// already-open stream, it doesn't build it.
// No `#include "HardwareLatencyCalculator.h"` — latency is computed
// after the stream exists, it doesn't affect construction.
// No `#include "ErrorCallbackHandler.h"` — error handling is provided
// by the caller as a parameter.
//
// Every one of these sibling headers will exist in its own file, and
// none of them is a dependency of the builder. This header is
// self-contained.
//
// ── Rule-of-five ─────────────────────────────────────────────────────
// Copy and move are both deleted. Rationale: the class holds a
// `std::string` for the error message and an `::oboe::AudioApi` for the
// last-observed API. Copying it would duplicate state that's meant to
// describe a single specific build attempt. Moving it would require the
// destination to also own the pending state, which makes no semantic
// sense. Deleting both forces callers to hold it by value and use it in
// place, which is the correct usage pattern.
//
// ── Threading contract ──────────────────────────────────────────────
// OboeStreamBuilder is **not thread-safe**. `build()` is expected to be
// called once per stream from a single thread during engine
// initialization. If you need to build streams on multiple threads,
// instantiate one builder per thread. This is documented here rather
// than enforced with a mutex because the expected usage pattern has
// zero contention; adding a mutex would be dead code.
//
// ── Example usage (from Engine.cpp, when authored) ──────────────────
//
//     futura::engine::oboe::AudioStreamConfig config;
//     config.sample_rate = 0;                    // device default
//     config.channel_count = 2;                  // stereo
//     config.format = ::oboe::AudioFormat::Float;  // float32 samples
//     config.performance_mode =
//         ::oboe::PerformanceMode::LowLatency;
//
//     futura::engine::oboe::OboeStreamBuilder builder;
//     auto stream = builder.build(config, this, this);
//     if (!stream) {
//         LOG_E("Stream build failed: %s",
//               builder.last_error().c_str());
//         return false;
//     }
//     // stream is now ready for engine_->start(stream)
//
// ── Forward-reference audit ──────────────────────────────────────────
// Includes:
//   • <oboe/Oboe.h>     — third-party, declared in .gitmodules + CMake
//   • <cstdint>         — stdlib
//   • <memory>          — stdlib
//   • <string>          — stdlib
//
// Zero project headers. Zero forward references. This file compiles
// as-is once `third_party/oboe/` is populated by `scripts/setup.sh`,
// which the README already documents as a prerequisite for any
// `:engine` build.

#ifndef FUTURA_ENGINE_OBOE_OBOE_STREAM_BUILDER_H
#define FUTURA_ENGINE_OBOE_OBOE_STREAM_BUILDER_H

#include <oboe/Oboe.h>

#include <cstdint>
#include <memory>
#include <string>

namespace futura::engine::oboe {

// ═════════════════════════════════════════════════════════════════════════
// Audio stream configuration
// ═════════════════════════════════════════════════════════════════════════

/**
 * Configuration for a single [OboeStreamBuilder::build] attempt.
 *
 * Every field has a default that matches the app's primary use case —
 * stereo, low-latency, float samples, media usage, music content type.
 * Callers who need a different stream profile (e.g., input for a future
 * feature, or a specific sample rate for bit-perfect output) override
 * the fields they care about and leave the rest at defaults.
 *
 * The defaults are chosen so that:
 *   • `AudioStreamConfig{}` opens a stream suitable for normal music
 *     playback on any modern Android device.
 *   • `fall_back_to_shared = true` means the stream will open in
 *     Exclusive mode when the system allows it, or Shared mode
 *     otherwise, without the caller needing to write fallback logic.
 */
struct AudioStreamConfig {

    /**
     * Requested sample rate in Hz. `0` means "use the device's native
     * rate". The device's native rate is the right default for two
     * reasons:
     *
     *   1. It avoids resampling when the source already matches
     *      (bit-perfect playback, feature #142).
     *   2. When the source doesn't match, the app's own resampler
     *      (feature #59) produces higher-quality output than the
     *      device's — so asking the device to resample is a
     *      last-resort.
     */
    int32_t sample_rate = 0;

    /**
     * Channel count. `2` = stereo, which is what virtually all music
     * uses. Mono sources are upmixed by the engine's PCM channel
     * remapper (feature #60) before reaching this point, so the stream
     * itself is always stereo unless the caller is building a
     * diagnostic or monitoring stream.
     */
    int32_t channel_count = 2;

    /**
     * Requested frames per data callback. `0` means "auto-tune" — the
     * device default, then adjusted by `DynamicBufferAutoTuner.h` if it
     * detects xruns. Non-zero values request a specific buffer size;
     * this is used by callers doing their own buffer tuning or by
     * `AAudioHardwarePath.h` when it needs a specific burst size for
     * MMAP.
     */
    int32_t frames_per_callback = 0;

    /**
     * Sample format. `Float` is the native format of every modern
     * Android audio path (AAudio and OpenSL ES both accept it) and the
     * format the engine's DSP chain operates in. Converting to Int16
     * only at the very last step (feature #71 —
     * `FloatToInt16Converter.h`) is the standard pipeline.
     */
    ::oboe::AudioFormat format = ::oboe::AudioFormat::Float;

    /**
     * Oboe performance mode. `LowLatency` is the correct default for a
     * music player with interactive controls — it requests AAudio MMAP
     * on compatible devices (~8ms round-trip) and OpenSL ES fast-path
     * on older ones. `None` is the fallback if low-latency fails
     * silently. `PowerSaving` should only be requested when battery is
     * more important than latency — e.g., in Sleep Dock mode.
     */
    ::oboe::PerformanceMode performance_mode =
        ::oboe::PerformanceMode::LowLatency;

    /**
     * Sharing mode. `Exclusive` requests dedicated access to the audio
     * device, bypassing Android's mixer — this is what enables
     * bit-perfect playback and lower latency. The system may refuse
     * (the audio HAL often owns the device), in which case the builder
     * falls back to `Shared` if `fall_back_to_shared` is true.
     */
    ::oboe::SharingMode sharing_mode = ::oboe::SharingMode::Exclusive;

    /**
     * Direction of the stream. `Output` for playback (the primary use
     * case). `Input` for a future capture path if one is ever added;
     * no current Futura feature captures audio (fingerprinting and
     * speech/music classification both operate on pre-decoded source
     * audio, not the microphone).
     */
    ::oboe::Direction direction = ::oboe::Direction::Output;

    /**
     * Android audio usage hint. `Media` tells the OS this stream should
     * get normal media-app treatment (ducking behavior, volume
     * category, routing priority). `Game` or `VoiceCommunication`
     * would route the stream differently and are wrong for a music
     * player.
     */
    ::oboe::Usage usage = ::oboe::Usage::Media;

    /**
     * Android content type hint. `Music` tells the OS this is
     * music content (affects spatialization routing, hearing-aid
     * processing, some OEM audio profiles). Correct for the app's
     * primary use case.
     */
    ::oboe::ContentType content_type = ::oboe::ContentType::Music;

    /**
     * Whether to fall back to `Shared` sharing mode when `Exclusive`
     * fails. `true` (the default) gives "try best, succeed gracefully"
     * behavior: on devices where the HAL preempts the device, the
     * stream still opens in Shared mode, which produces the same audio
     * (just through the OS mixer). Set to `false` only for diagnostic
     * builds that want to know the Exclusive attempt failed rather
     * than silently degrade.
     */
    bool fall_back_to_shared = true;
};

// ═════════════════════════════════════════════════════════════════════════
// Builder
// ═════════════════════════════════════════════════════════════════════════

/**
 * Stateless-per-attempt Oboe stream builder.
 *
 * Each `build()` call attempts to open a fresh stream with the supplied
 * configuration. The class holds only the last-attempt error state (for
 * the caller to inspect), no other persistent state. It can be reused
 * for multiple `build()` calls — each call overwrites `last_error_` and
 * `opened_with_api_`.
 *
 * See the file header for the full design contract, threading notes,
 * and example usage.
 */
class OboeStreamBuilder {
public:

    OboeStreamBuilder() = default;
    ~OboeStreamBuilder() = default;

    // Rule of five — see the file header's note on why both are deleted.
    OboeStreamBuilder(const OboeStreamBuilder&) = delete;
    OboeStreamBuilder& operator=(const OboeStreamBuilder&) = delete;
    OboeStreamBuilder(OboeStreamBuilder&&) = delete;
    OboeStreamBuilder& operator=(OboeStreamBuilder&&) = delete;

    /**
     * Build and open an output stream with the given configuration.
     *
     * If `config.sharing_mode` is `Exclusive` and `fall_back_to_shared`
     * is `true` and the first attempt fails, retries with `Shared`
     * mode automatically. Uses `::oboe::AudioApi::Unspecified`, meaning
     * Oboe chooses the API (AAudio on API 26+, OpenSL ES on older
     * devices).
     *
     * @param config  Stream configuration. See [AudioStreamConfig].
     * @param data_callback  Pointer to the `AudioStreamDataCallback`
     *        that will fill audio buffers. Typically the `Engine`
     *        instance itself. Must outlive the returned stream. May be
     *        null if the caller intends to use blocking I/O (not
     *        recommended — the engine is designed for callback mode).
     * @param error_callback  Pointer to the `AudioStreamErrorCallback`
     *        that will be notified of asynchronous errors (device
     *        disconnect, xrun). Must outlive the returned stream. May
     *        be null if the caller doesn't want error notifications
     *        (not recommended — the engine relies on the error callback
     *        to trigger stream recovery).
     *
     * @return A `shared_ptr` to the opened stream, or `nullptr` on any
     *         failure. Callers should check `last_error()` and
     *         `last_result()` for diagnostic detail.
     */
    std::shared_ptr<::oboe::AudioStream> build(
        const AudioStreamConfig& config,
        ::oboe::AudioStreamDataCallback* data_callback,
        ::oboe::AudioStreamErrorCallback* error_callback);

    /**
     * Build and open a stream, forcing a specific audio API.
     *
     * Used by `AAudioHardwarePath.h` (feature #52) to force
     * `AAudio` and by `OpenSLESFallback.h` (feature #53) to force
     * `OpenSLES`. All other semantics match [build].
     *
     * @param preferred_api The `::oboe::AudioApi` to attempt. Use
     *        `::oboe::AudioApi::Unspecified` to match `build()`'s
     *        behavior.
     */
    std::shared_ptr<::oboe::AudioStream> build_with_api(
        const AudioStreamConfig& config,
        ::oboe::AudioStreamDataCallback* data_callback,
        ::oboe::AudioStreamErrorCallback* error_callback,
        ::oboe::AudioApi preferred_api);

    /**
     * Human-readable description of the last failure. Empty if the last
     * `build()` succeeded. Populated from `::oboe::convertToText()` — the
     * same strings Oboe's own logging uses.
     */
    const std::string& last_error() const noexcept { return last_error_; }

    /**
     * Machine-readable `::oboe::Result` of the last `build()` attempt.
     * `Result::OK` on success. Callers who need to make a specific
     * decision based on the exact error (e.g., retry only on
     * `ErrorDisconnected`) read this; callers who just want to log
     * read `last_error()`.
     */
    ::oboe::Result last_result() const noexcept { return last_result_; }

    /**
     * Which API the successful stream was opened with
     * (`AAudio` or `OpenSLES`). `Unspecified` before any successful
     * build. Useful for telemetry and for the Stream Inspector's
     * "AUDIO HAL" row.
     */
    ::oboe::AudioApi opened_with_api() const noexcept { return opened_with_api_; }

    /**
     * Clear the last-attempt state. Optional — every `build()` call
     * resets it anyway. Provided for callers who want to inspect a
     * specific failed attempt's state without another attempt
     * overwriting it.
     */
    void reset() noexcept;

private:

    /**
     * Internal single-attempt helper. Sets up the Oboe builder, opens
     * the stream, and updates `last_error_` / `last_result_` /
     * `opened_with_api_` based on the result. Called up to twice by
     * `build_with_api` (once for the requested sharing mode, once for
     * the fallback).
     */
    std::shared_ptr<::oboe::AudioStream> try_open(
        const AudioStreamConfig& config,
        ::oboe::AudioStreamDataCallback* data_callback,
        ::oboe::AudioStreamErrorCallback* error_callback,
        ::oboe::AudioApi api,
        ::oboe::SharingMode sharing_mode);

    std::string last_error_;
    ::oboe::Result last_result_ = ::oboe::Result::OK;
    ::oboe::AudioApi opened_with_api_ = ::oboe::AudioApi::Unspecified;
};

// ═════════════════════════════════════════════════════════════════════════
// Inline implementations
// ═════════════════════════════════════════════════════════════════════════

inline std::shared_ptr<::oboe::AudioStream> OboeStreamBuilder::build(
    const AudioStreamConfig& config,
    ::oboe::AudioStreamDataCallback* data_callback,
    ::oboe::AudioStreamErrorCallback* error_callback) {
    return build_with_api(
        config,
        data_callback,
        error_callback,
        ::oboe::AudioApi::Unspecified
    );
}

inline std::shared_ptr<::oboe::AudioStream> OboeStreamBuilder::build_with_api(
    const AudioStreamConfig& config,
    ::oboe::AudioStreamDataCallback* data_callback,
    ::oboe::AudioStreamErrorCallback* error_callback,
    ::oboe::AudioApi preferred_api) {

    // Attempt 1: requested sharing mode.
    auto stream = try_open(
        config,
        data_callback,
        error_callback,
        preferred_api,
        config.sharing_mode
    );
    if (stream) {
        return stream;
    }

    // Attempt 2: fallback to Shared, if the requested mode was anything
    // other than Shared and the caller hasn't disabled fallback.
    //
    // This is the correct place for the fallback — doing it inside
    // try_open would make that helper implicitly two-attempt, which
    // obscures the behavior. The build_with_api method makes the
    // two-attempt strategy explicit and easy to see.
    const bool can_fall_back =
        config.fall_back_to_shared &&
        config.sharing_mode != ::oboe::SharingMode::Shared;
    if (can_fall_back) {
        stream = try_open(
            config,
            data_callback,
            error_callback,
            preferred_api,
            ::oboe::SharingMode::Shared
        );
        if (stream) {
            return stream;
        }
    }

    return nullptr;
}

inline std::shared_ptr<::oboe::AudioStream> OboeStreamBuilder::try_open(
    const AudioStreamConfig& config,
    ::oboe::AudioStreamDataCallback* data_callback,
    ::oboe::AudioStreamErrorCallback* error_callback,
    ::oboe::AudioApi api,
    ::oboe::SharingMode sharing_mode) {

    ::oboe::AudioStreamBuilder builder;

    builder.setDirection(config.direction);
    builder.setSampleRate(config.sample_rate);
    builder.setChannelCount(config.channel_count);
    builder.setFormat(config.format);
    builder.setPerformanceMode(config.performance_mode);
    builder.setSharingMode(sharing_mode);
    builder.setAudioApi(api);
    builder.setUsage(config.usage);
    builder.setContentType(config.content_type);

    if (config.frames_per_callback > 0) {
        builder.setFramesPerCallback(config.frames_per_callback);
    }

    if (data_callback) {
        builder.setDataCallback(data_callback);
    }
    if (error_callback) {
        builder.setErrorCallback(error_callback);
    }

    // openStream() returns ResultWithValue<shared_ptr<AudioStream>>.
    // ResultWithValue has operator bool() for the "did it succeed?"
    // check, .value() for the payload, and .error() for the ::oboe::Result.
    auto result = builder.openStream();

    if (!result) {
        last_result_ = result.error();
        last_error_ = ::oboe::convertToText(result.error());
        return nullptr;
    }

    auto stream = result.value();
    last_result_ = ::oboe::Result::OK;
    last_error_.clear();
    opened_with_api_ = stream->getAudioApi();
    return stream;
}

inline void OboeStreamBuilder::reset() noexcept {
    last_error_.clear();
    last_result_ = ::oboe::Result::OK;
    opened_with_api_ = ::oboe::AudioApi::Unspecified;
}

}  // namespace futura::engine::oboe

#endif  // FUTURA_ENGINE_OBOE_OBOE_STREAM_BUILDER_H