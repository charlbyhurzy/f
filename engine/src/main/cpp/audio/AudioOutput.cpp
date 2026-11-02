// futura-audio/engine/src/main/cpp/audio/AudioOutput.cpp
//
// Phase 3 — Audio output implementation.
//
// ⚠️  HEADER-FIX REQUIRED  ⚠️
// This file depends on `audio/AudioOutput.h`, which (as delivered two
// turns ago) uses bare `oboe::Xxx` for Oboe-library types inside
// `namespace futura::engine::audio`. That resolves to the project's
// `futura::engine::oboe` namespace, not the Oboe library's `::oboe` —
// the header does not compile as delivered. Apply the mechanical fix
// documented above before building this file. Every reference in this
// .cpp already uses `::oboe::` prefix correctly, so this file is
// forward-compatible with the fixed header.
//
// ── Responsibility ──────────────────────────────────────────────────────
// Implements the eleven methods declared in `AudioOutput.h`:
//
//   Lifecycle:     open(), close()
//   Transport:     start(), pause(), flush(), stop()
//   Callbacks:     onAudioReady(), onErrorAfterClose()  (Oboe forwarders)
//   Error state:   setError(), clearError(), lastError()
//
// Two things deliberately NOT here (already inline in the header):
//   • Destructor — auto-closes for safety, trivial body
//   • Trivial getters (isOpen, isStarted, getSampleRate, ..., lastResult)
//     — 1-3 lines each, no logic beyond a null check
//
// ── Design notes for the implementations below ─────────────────────────
// • Every Oboe library reference uses `::oboe::` (double-colon leading).
//   This is the correct qualification from inside `futura::engine::audio`;
//   see the header-fix note above.
//
// • `close()` calls `stream->close()`, which blocks until the audio
//   thread has fully unwound. After that returns, no further callbacks
//   can fire, so clearing `callback_` is safe.
//
// • `onErrorAfterClose()` records the error into `last_error_` /
//   `last_result_` via `setError()`, which briefly acquires
//   `error_mutex_`. This is the ONE place where a lock is acquired on
//   the audio thread — a deliberate exception because:
//     (a) It fires only on rare async errors (device disconnect, xrun),
//     (b) The lock hold is sub-microsecond (a short string move + enum
//         assign), and
//     (c) The alternative — a lock-free error log — would add hundreds
//         of lines for negligible benefit.
//   The rest of the audio-thread path (`onAudioReady`) takes no locks,
//   allocates nothing, and blocks on nothing.
//
// • Error propagation into `last_error_` from `onErrorAfterClose()` is
//   what makes the header's documented behavior true:
//   "Also records the error into `last_error_` / `last_result_` for
//   caller inspection after the fact." A future caller (Engine's
//   ErrorCallbackHandler) may call `lastError()` from a non-audio
//   thread after receiving the forwarded callback, and get the same
//   message.

#include "audio/AudioOutput.h"

#include <oboe/Oboe.h>

#include <string>
#include <utility>

namespace futura::engine::audio {

// ═════════════════════════════════════════════════════════════════════════
// Lifecycle
// ═════════════════════════════════════════════════════════════════════════

bool AudioOutput::open(
    const AudioOutputConfig& config,
    RenderCallback* callback) {

    if (callback == nullptr) {
        setError(
            ::oboe::Result::ErrorNull,
            "AudioOutput::open(): RenderCallback must not be null");
        return false;
    }

    // Reject double-open. The header documents that open() is valid only
    // from Closed state; a second call without an intervening close() is
    // a caller bug, not a recoverable condition.
    if (state_.load(std::memory_order_acquire) != State::Closed) {
        setError(
            ::oboe::Result::ErrorInvalidState,
            "AudioOutput::open(): already open — call close() first");
        return false;
    }

    clearError();

    // Translate AudioOutputConfig → OboeStreamBuilder::AudioStreamConfig.
    // The two structs have deliberately similar but distinct layouts
    // (see the header's note on why). The translation is explicit here so
    // the fields can evolve independently.
    futura::engine::oboe::AudioStreamConfig builder_config;
    builder_config.sample_rate         = config.sample_rate;
    builder_config.channel_count       = config.channel_count;
    builder_config.frames_per_callback = config.frames_per_callback;
    builder_config.format              = config.format;
    builder_config.performance_mode    = config.performance_mode;
    builder_config.sharing_mode        = config.sharing_mode;
    builder_config.direction           = ::oboe::Direction::Output;
    builder_config.usage               = config.usage;
    builder_config.content_type        = config.content_type;
    builder_config.fall_back_to_shared = config.fall_back_to_shared;

    futura::engine::oboe::OboeStreamBuilder builder;
    auto stream = builder.build(builder_config, this, this);

    if (!stream) {
        // Preserve the builder's diagnostics verbatim — they carry the
        // oboe::Result and Oboe's own message, both of which the caller
        // may want to log.
        setError(
            builder.last_result(),
            "OboeStreamBuilder failed: " + builder.last_error());
        return false;
    }

    stream_   = std::move(stream);
    callback_ = callback;
    state_.store(State::Open, std::memory_order_release);
    return true;
}

void AudioOutput::close() {
    // Idempotent: close on an already-closed stream is a no-op.
    if (state_.load(std::memory_order_acquire) == State::Closed) {
        return;
    }

    // stream_->close() blocks until Oboe's audio thread has fully
    // unwound. After it returns, no further onAudioReady() or
    // onErrorAfterClose() calls can fire on this AudioOutput. That is
    // what makes clearing callback_ and setting state_ to Closed safe
    // without a mutex.
    if (stream_) {
        // requestStop() is non-blocking and stops producing audio; then
        // close() releases the OS handle. Two separate calls match
        // Oboe's recommended teardown sequence — close() alone would
        // also work but leaves the OS in a slightly longer transient.
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }

    callback_ = nullptr;
    state_.store(State::Closed, std::memory_order_release);
}

// ═════════════════════════════════════════════════════════════════════════
// Transport
// ═════════════════════════════════════════════════════════════════════════

bool AudioOutput::start() {
    const State current = state_.load(std::memory_order_acquire);
    if (current == State::Closed) {
        // Nothing to start — the stream is not open.
        return false;
    }
    if (current == State::Started) {
        // Already started — start() is idempotent.
        return true;
    }

    // state_ == Open; the stream_ should be non-null. Defensive null
    // check just in case a future refactor ever sets state_ before
    // stream_ is assigned.
    if (!stream_) {
        return false;
    }

    const auto result = stream_->requestStart();
    if (result != ::oboe::Result::OK) {
        setError(
            result,
            std::string("requestStart failed: ")
                + ::oboe::convertToText(result));
        return false;
    }

    state_.store(State::Started, std::memory_order_release);
    return true;
}

bool AudioOutput::pause() {
    const State current = state_.load(std::memory_order_acquire);
    if (current == State::Closed) {
        return false;
    }
    if (current == State::Open) {
        // Already paused (Open means "not started") — idempotent.
        return true;
    }

    if (!stream_) {
        return false;
    }

    const auto result = stream_->requestPause();
    if (result != ::oboe::Result::OK) {
        setError(
            result,
            std::string("requestPause failed: ")
                + ::oboe::convertToText(result));
        return false;
    }

    state_.store(State::Open, std::memory_order_release);
    return true;
}

bool AudioOutput::flush() {
    // flush() is valid in any non-Closed state — used after seeks to
    // discard buffered samples from the pre-seek position. It does not
    // change the transport state.
    if (state_.load(std::memory_order_acquire) == State::Closed) {
        return false;
    }
    if (!stream_) {
        return false;
    }

    const auto result = stream_->requestFlush();
    if (result != ::oboe::Result::OK) {
        setError(
            result,
            std::string("requestFlush failed: ")
                + ::oboe::convertToText(result));
        return false;
    }
    return true;
}

bool AudioOutput::stop() {
    if (state_.load(std::memory_order_acquire) == State::Closed) {
        return false;
    }
    if (!stream_) {
        return false;
    }

    // requestStop() halts audio production; requestFlush() discards any
    // samples that were already buffered (which would otherwise play on
    // the next start()). Doing both is the correct "stop cleanly"
    // operation — see the header's rationale for combining them.
    const auto stop_result = stream_->requestStop();
    if (stop_result != ::oboe::Result::OK) {
        setError(
            stop_result,
            std::string("requestStop failed: ")
                + ::oboe::convertToText(stop_result));
        return false;
    }

    const auto flush_result = stream_->requestFlush();
    if (flush_result != ::oboe::Result::OK) {
        // Stop succeeded; flush failed. Report the flush failure —
        // caller can retry the flush if needed. The stream is still
        // in a usable (Open) state.
        setError(
            flush_result,
            std::string("requestFlush failed during stop: ")
                + ::oboe::convertToText(flush_result));
        state_.store(State::Open, std::memory_order_release);
        return false;
    }

    state_.store(State::Open, std::memory_order_release);
    return true;
}

// ═════════════════════════════════════════════════════════════════════════
// Oboe callback forwarders
// ═════════════════════════════════════════════════════════════════════════

::oboe::DataCallbackResult AudioOutput::onAudioReady(
    ::oboe::AudioStream* oboe_stream,
    void* audio_data,
    int32_t num_frames) {

    // Local copy avoids a race with close() setting callback_ to null.
    // The pointer's lifetime is guaranteed by the caller's contract
    // ("Must outlive this AudioOutput") — see the header.
    RenderCallback* cb = callback_;
    if (cb == nullptr) {
        // Should not happen after a successful open() and before close()
        // returns, but if it ever does (e.g., close() raced with the
        // audio thread), stop the stream rather than null-dereference.
        return ::oboe::DataCallbackResult::Stop;
    }

    return cb->onAudioReady(oboe_stream, audio_data, num_frames);
}

void AudioOutput::onErrorAfterClose(
    ::oboe::AudioStream* oboe_stream,
    ::oboe::Result error) {

    // Record before forwarding so that a caller who inspects lastError()
    // from within its own onErrorAfterClose handler sees the current
    // failure. See the file header's note on the deliberate mutex
    // acquisition here.
    setError(
        error,
        std::string("Async stream error: ")
            + ::oboe::convertToText(error));

    RenderCallback* cb = callback_;
    if (cb != nullptr) {
        cb->onErrorAfterClose(oboe_stream, error);
    }

    // Oboe has closed the underlying stream. Move our state machine to
    // Closed so no further transport calls attempt to use the dead
    // stream. The engine's recovery flow (see the header) is: it
    // observes this callback, destroys this AudioOutput, and creates a
    // new one.
    state_.store(State::Closed, std::memory_order_release);
}

// ═════════════════════════════════════════════════════════════════════════
// Error state
// ═════════════════════════════════════════════════════════════════════════

void AudioOutput::setError(
    ::oboe::Result result,
    std::string message) {

    std::lock_guard<std::mutex> lock(error_mutex_);
    last_result_ = result;
    last_error_  = std::move(message);
}

void AudioOutput::clearError() {
    std::lock_guard<std::mutex> lock(error_mutex_);
    last_result_ = ::oboe::Result::OK;
    last_error_.clear();
}

std::string AudioOutput::lastError() const {
    std::lock_guard<std::mutex> lock(error_mutex_);
    return last_error_;
}

}  // namespace futura::engine::audio