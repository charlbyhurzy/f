// futura-audio/engine/src/main/cpp/decode/ResamplerEngine.h
//
// Phase 3 — libsamplerate resampler (feature #59). Wraps SRC_STATE;
// converts interleaved float32 from source rate to device rate.
//
// Quality tiers (Kotlin ResamplerQuality.nativeValue → SRC_*):
//   0 LOW      → SRC_LINEAR
//   1 BALANCED → SRC_SINC_FASTEST
//   2 HIGH     → SRC_SINC_MEDIUM_QUALITY
//   3 BEST     → SRC_SINC_BEST_QUALITY
//
// Pass-through (input_rate == output_rate) short-circuits to memcpy.
//
// EOF: call set_end_of_input(), then loop flush_into() until it returns
// 0. That drains the resampler's internal Sinc tail (~10ms at 48 kHz).

#ifndef FUTURA_ENGINE_DECODE_RESAMPLER_ENGINE_H
#define FUTURA_ENGINE_DECODE_RESAMPLER_ENGINE_H

extern "C" {
#include <samplerate.h>
}

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>

namespace futura::engine::decode {

class ResamplerEngine {
public:
    ResamplerEngine() noexcept = default;

    ~ResamplerEngine() { close(); }

    ResamplerEngine(const ResamplerEngine&) = delete;
    ResamplerEngine& operator=(const ResamplerEngine&) = delete;
    ResamplerEngine(ResamplerEngine&&) = delete;
    ResamplerEngine& operator=(ResamplerEngine&&) = delete;

    // ── Lifecycle (control thread) ─────────────────────────────────

    // Returns true on success. Reinit tears down the old state first.
    // Blocking (~5ms for src_new + filter init).
    bool initialize(int32_t input_rate, int32_t output_rate,
                    int32_t channels, int32_t quality) noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);

            if (state_) {
                src_delete(state_);
                state_ = nullptr;
            }
            initialized_.store(false, std::memory_order_release);
            flush_complete_.store(false, std::memory_order_relaxed);

            if (input_rate <= 0 || output_rate <= 0 || channels <= 0) {
                set_error_locked("invalid rate or channel count");
                return false;
            }

            int error = 0;
            SRC_STATE* new_state = src_new(map_quality(quality), channels, &error);
            if (!new_state) {
                set_error_locked(std::string("src_new failed: ") +
                                 src_strerror(error));
                return false;
            }

            state_      = new_state;
            input_rate_  = input_rate;
            output_rate_ = output_rate;
            channels_    = channels;
            quality_     = quality;
            ratio_       = static_cast<double>(output_rate) /
                           static_cast<double>(input_rate);
            is_passthrough_.store(input_rate == output_rate,
                                  std::memory_order_release);
            initialized_.store(true, std::memory_order_release);
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("initialize: ") + e.what());
        } catch (...) {
            set_error("initialize: unknown exception");
        }
        return false;
    }

    void close() noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);
            if (state_) {
                src_delete(state_);
                state_ = nullptr;
            }
            initialized_.store(false, std::memory_order_release);
            is_passthrough_.store(false, std::memory_order_release);
            flush_complete_.store(false, std::memory_order_relaxed);
            input_rate_  = 0;
            output_rate_ = 0;
            channels_    = 0;
            quality_     = 1;
            ratio_       = 1.0;
            end_of_input_ = false;
        } catch (...) { /* noexcept */ }
    }

    // ── Processing (decode thread) ─────────────────────────────────

    // Resample up to in_frames. Returns frames written to out.
    // Caller MUST size out for max_out_frames = in_frames*ratio + 64.
    // Never blocks: if the control thread is reconfiguring, drops this
    // call (returns 0) rather than stalling the audio path.
    size_t process(const float* in, size_t in_frames,
                   float* out, size_t out_capacity_frames) noexcept {
        try {
            if (!in || !out || in_frames == 0 || out_capacity_frames == 0) {
                return 0;
            }
            if (is_passthrough_.load(std::memory_order_acquire)) {
                const size_t ch = static_cast<size_t>(channels_);
                const size_t to_copy = in_frames < out_capacity_frames
                                     ? in_frames : out_capacity_frames;
                std::memcpy(out, in, to_copy * ch * sizeof(float));
                return to_copy;
            }

            std::unique_lock<std::mutex> lock(state_mutex_, std::try_to_lock);
            if (!lock.owns_lock() || !state_) return 0;

            SRC_DATA data;
            std::memset(&data, 0, sizeof(data));
            data.data_in       = in;
            data.input_frames  = static_cast<long>(in_frames);
            data.data_out      = out;
            data.output_frames = static_cast<long>(out_capacity_frames);
            data.end_of_input  = end_of_input_ ? 1 : 0;
            data.src_ratio     = ratio_;

            const int ret = src_process(state_, &data);
            if (ret != 0) {
                set_error_locked(std::string("src_process: ") +
                                 src_strerror(ret));
                return 0;
            }
            const size_t produced = static_cast<size_t>(data.output_frames_gen);
            last_output_frames_gen_.store(produced, std::memory_order_relaxed);
            return produced;
        } catch (...) {
            return 0;
        }
    }

    // Signal that source has reached EOF. Subsequent process() calls pass
    // end_of_input=1 to libsamplerate.
    void set_end_of_input(bool end = true) noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);
            end_of_input_ = end;
        } catch (...) { /* noexcept */ }
    }

    // Drain the resampler's internal Sinc buffer after EOF.
    // Caller loops: while (flush_into(out, cap) > 0) { ... }
    // Returns 0 when fully drained (and marks flush_complete_=true).
    size_t flush_into(float* out, size_t out_capacity_frames) noexcept {
        try {
            if (!out || out_capacity_frames == 0) return 0;
            if (flush_complete_.load(std::memory_order_acquire)) return 0;
            if (is_passthrough_.load(std::memory_order_acquire)) {
                flush_complete_.store(true, std::memory_order_release);
                return 0;
            }

            std::unique_lock<std::mutex> lock(state_mutex_, std::try_to_lock);
            if (!lock.owns_lock() || !state_) return 0;

            SRC_DATA data;
            std::memset(&data, 0, sizeof(data));
            data.data_in       = nullptr;
            data.input_frames  = 0;
            data.data_out      = out;
            data.output_frames = static_cast<long>(out_capacity_frames);
            data.end_of_input  = 1;
            data.src_ratio     = ratio_;

            const int ret = src_process(state_, &data);
            if (ret != 0) {
                set_error_locked(std::string("src_process(flush): ") +
                                 src_strerror(ret));
                return 0;
            }
            const size_t produced = static_cast<size_t>(data.output_frames_gen);
            if (produced == 0) {
                flush_complete_.store(true, std::memory_order_release);
            }
            return produced;
        } catch (...) {
            return 0;
        }
    }

    // Reset internal filter state (post-seek). Keeps configuration.
    void reset() noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);
            if (state_) src_reset(state_);
            end_of_input_ = false;
            flush_complete_.store(false, std::memory_order_release);
            last_output_frames_gen_.store(0, std::memory_order_relaxed);
        } catch (...) { /* noexcept */ }
    }

    // ── Introspection (safe from any thread) ───────────────────────

    bool is_initialized() const noexcept {
        return initialized_.load(std::memory_order_acquire);
    }
    bool is_passthrough() const noexcept {
        return is_passthrough_.load(std::memory_order_acquire);
    }
    bool flush_complete() const noexcept {
        return flush_complete_.load(std::memory_order_acquire);
    }
    int32_t input_rate()  const noexcept { return input_rate_; }
    int32_t output_rate() const noexcept { return output_rate_; }
    int32_t channels()    const noexcept { return channels_; }
    int32_t quality()     const noexcept { return quality_; }
    double  ratio()       const noexcept { return ratio_; }

    std::string last_error() const noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            return last_error_;
        } catch (...) { return {}; }
    }

private:
    static int map_quality(int q) noexcept {
        switch (q) {
            case 0:  return SRC_LINEAR;
            case 1:  return SRC_SINC_FASTEST;
            case 2:  return SRC_SINC_MEDIUM_QUALITY;
            case 3:  return SRC_SINC_BEST_QUALITY;
            default: return SRC_SINC_FASTEST;
        }
    }

    // Caller must hold state_mutex_ (initialize/close/process/flush_into
    // all hold it when calling this).
    void set_error_locked(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    // Same, but acquires state_mutex_ itself. Used by catch blocks in
    // initialize() and close() before state is locked.
    void set_error(const std::string& msg) noexcept {
        set_error_locked(msg);
    }

    // SRC_STATE pointer is protected by state_mutex_ (rule 7).
    mutable std::mutex state_mutex_;
    SRC_STATE*         state_ = nullptr;

    // Immutable-after-initialize config — read without lock elsewhere.
    int32_t input_rate_  = 0;
    int32_t output_rate_ = 0;
    std::atomic<int32_t> channels_{0};   // read by the decode thread
    int32_t quality_     = 1;
    std::atomic<double>  ratio_{1.0};    // read by the decode thread
    bool    end_of_input_ = false;

    // Atomics for lock-free reads on the audio path.
    std::atomic<bool>   initialized_{false};
    std::atomic<bool>   is_passthrough_{false};
    std::atomic<bool>   flush_complete_{false};
    std::atomic<size_t> last_output_frames_gen_{0};

    mutable std::mutex error_mutex_;
    std::string        last_error_;
};

}  // namespace futura::engine::decode

#endif  // FUTURA_ENGINE_DECODE_RESAMPLER_ENGINE_H