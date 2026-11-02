// futura-audio/engine/src/main/cpp/core/StreamStateMachine.h
//
// Phase 3 — Stream state machine (feature #64). Authoritative state
// tracking for the Oboe stream, distinct from the user-facing
// NativePlaybackState and from AudioOutput's internal Closed/Open/Started.
//
// State values match NativeStreamState.nativeValue in NativePlayerBridge.kt
// and the constants in Engine.cpp.
//
// Growth notes:
//   • oboe/BufferUnderrunRecovery.h (tree) — will drive DISCONNECTED→
//     CLOSING→OPENING recovery chains through this machine.
//   • oboe/ErrorCallbackHandler.h (tree) — will observe ERROR transitions
//     to decide whether to attempt reopen.

#ifndef FUTURA_ENGINE_CORE_STREAM_STATE_MACHINE_H
#define FUTURA_ENGINE_CORE_STREAM_STATE_MACHINE_H

#include <atomic>
#include <cstdint>
#include <mutex>

namespace futura::engine::core {

enum class StreamState : uint8_t {
    Uninitialized = 0,
    Opening       = 1,
    Started       = 2,
    Paused        = 3,
    Flushing      = 4,
    Stopped       = 5,
    Closing       = 6,
    Disconnected  = 7,
    Error         = 8,
};

enum class TransitionResult : uint8_t {
    Ok                = 0,
    InvalidTransition = 1,
    Busy              = 2,
};

class StreamStateMachine {
public:
    StreamStateMachine() noexcept
        : state_(StreamState::Uninitialized) {}

    StreamStateMachine(const StreamStateMachine&) = delete;
    StreamStateMachine& operator=(const StreamStateMachine&) = delete;
    StreamStateMachine(StreamStateMachine&&) = delete;
    StreamStateMachine& operator=(StreamStateMachine&&) = delete;

    // Lock-free read. Safe from any thread including the audio thread.
    StreamState current() const noexcept {
        return state_.load(std::memory_order_acquire);
    }

    // Control-thread transition. Blocks if another transition is in flight.
    // Rule 7: mutex-protected critical section (validate + store).
    TransitionResult transition(StreamState next) noexcept {
        try {
            std::lock_guard<std::mutex> lock(transition_mutex_);
            const StreamState from = state_.load(std::memory_order_acquire);
            if (from == next) return TransitionResult::Ok;
            if (!is_valid_transition(from, next)) {
                return TransitionResult::InvalidTransition;
            }
            state_.store(next, std::memory_order_release);
            return TransitionResult::Ok;
        } catch (...) {
            return TransitionResult::Busy;
        }
    }

    // Audio-thread transition. Uses try_lock — never blocks the render
    // thread. On contention returns Busy; the caller (typically
    // Engine::onErrorAfterClose) may fall back to force() if a stale
    // non-transitioned state would be dangerous.
    TransitionResult try_transition(StreamState next) noexcept {
        try {
            std::unique_lock<std::mutex> lock(
                transition_mutex_, std::try_to_lock);
            if (!lock.owns_lock()) return TransitionResult::Busy;
            const StreamState from = state_.load(std::memory_order_acquire);
            if (from == next) return TransitionResult::Ok;
            if (!is_valid_transition(from, next)) {
                return TransitionResult::InvalidTransition;
            }
            state_.store(next, std::memory_order_release);
            return TransitionResult::Ok;
        } catch (...) {
            return TransitionResult::Busy;
        }
    }

    // Force state without validation. For recovery paths only
    // (Engine::reset, post-error re-init). Best-effort if the mutex
    // is contended — rule 6 requires the throw guard regardless.
    void force(StreamState next) noexcept {
        try {
            std::lock_guard<std::mutex> lock(transition_mutex_);
            state_.store(next, std::memory_order_release);
        } catch (...) {
            state_.store(next, std::memory_order_release);
        }
    }

    static const char* name_of(StreamState s) noexcept {
        switch (s) {
            case StreamState::Uninitialized: return "UNINITIALIZED";
            case StreamState::Opening:       return "OPENING";
            case StreamState::Started:       return "STARTED";
            case StreamState::Paused:        return "PAUSED";
            case StreamState::Flushing:      return "FLUSHING";
            case StreamState::Stopped:       return "STOPPED";
            case StreamState::Closing:       return "CLOSING";
            case StreamState::Disconnected:  return "DISCONNECTED";
            case StreamState::Error:         return "ERROR";
        }
        return "UNKNOWN";
    }

private:
    static bool is_valid_transition(StreamState from, StreamState to) noexcept {
        switch (from) {
            case StreamState::Uninitialized:
                return to == StreamState::Opening ||
                       to == StreamState::Closing;
            case StreamState::Opening:
                return to == StreamState::Started ||
                       to == StreamState::Paused ||
                       to == StreamState::Stopped ||
                       to == StreamState::Closing ||
                       to == StreamState::Error;
            case StreamState::Started:
                return to == StreamState::Paused ||
                       to == StreamState::Flushing ||
                       to == StreamState::Stopped ||
                       to == StreamState::Disconnected ||
                       to == StreamState::Error ||
                       to == StreamState::Closing;
            case StreamState::Paused:
                return to == StreamState::Started ||
                       to == StreamState::Flushing ||
                       to == StreamState::Stopped ||
                       to == StreamState::Disconnected ||
                       to == StreamState::Error ||
                       to == StreamState::Closing;
            case StreamState::Flushing:
                return to == StreamState::Started ||
                       to == StreamState::Paused ||
                       to == StreamState::Stopped ||
                       to == StreamState::Disconnected ||
                       to == StreamState::Closing ||
                       to == StreamState::Error;
            case StreamState::Stopped:
                return to == StreamState::Started ||
                       to == StreamState::Closing ||
                       to == StreamState::Error;
            case StreamState::Closing:
                return to == StreamState::Uninitialized ||
                       to == StreamState::Error;
            case StreamState::Disconnected:
                return to == StreamState::Closing ||
                       to == StreamState::Error;
            case StreamState::Error:
                return to == StreamState::Closing ||
                       to == StreamState::Uninitialized;
        }
        return false;
    }

    std::atomic<StreamState> state_;
    mutable std::mutex       transition_mutex_;
};

}  // namespace futura::engine::core

#endif  // FUTURA_ENGINE_CORE_STREAM_STATE_MACHINE_H