// futura-audio/engine/src/main/cpp/core/ZeroAllocRenderLoop.h
//
// Phase 3 — Real-time render loop (feature #56). Zero-allocation,
// real-time-safe. Reads from the ring buffer, applies volume ramping,
// fills underruns with silence. Sits between LockFreeRingBuffer and
// AudioOutput's callback.
//
// Growth notes:
//   • dsp/DspChain.h (tree) — DSP processing applies here, between
//     ring buffer read and volume ramp.
//   • oboe/XRunDetector.h (tree) — will consume xrun_count() to
//     distinguish transient vs chronic underruns.
//   • decode/ResamplerEngine.h — already available; the caller (Engine)
//     places it upstream of the ring buffer, not here.

#ifndef FUTURA_ENGINE_CORE_ZERO_ALLOC_RENDER_LOOP_H
#define FUTURA_ENGINE_CORE_ZERO_ALLOC_RENDER_LOOP_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>

#include "core/LockFreeRingBuffer.h"

namespace futura::engine::core {

class ZeroAllocRenderLoop {
public:
    /**
     * @param ring_buffer Filled by the decoder thread; drained here on
     *        the audio thread. Must outlive the render loop.
     */
    explicit ZeroAllocRenderLoop(LockFreeRingBuffer<float>* ring_buffer) noexcept
        : ring_buffer_(ring_buffer)
        , audio_snapshot_(config_)  // prime the local snapshot with defaults
    {}

    ZeroAllocRenderLoop(const ZeroAllocRenderLoop&) = delete;
    ZeroAllocRenderLoop& operator=(const ZeroAllocRenderLoop&) = delete;
    ZeroAllocRenderLoop(ZeroAllocRenderLoop&&) = delete;
    ZeroAllocRenderLoop& operator=(ZeroAllocRenderLoop&&) = delete;

    // ── Control-thread API ──────────────────────────────────────────

    void set_volume(float linear) noexcept {
        // NaN fails every comparison, so reject it explicitly; otherwise it
        // would stick in the smoother and silence the output permanently.
        if (!(linear >= 0.0f)) linear = 0.0f;
        if (linear > 1.0f) linear = 1.0f;
        std::lock_guard<std::mutex> lock(config_mutex_);
        config_.volume = linear;
    }

    float get_volume() const noexcept {
        std::lock_guard<std::mutex> lock(config_mutex_);
        return config_.volume;
    }

    void set_silence_on_underrun(bool enabled) noexcept {
        std::lock_guard<std::mutex> lock(config_mutex_);
        config_.silence_on_underrun = enabled;
    }

    uint64_t frames_written() const noexcept {
        return frames_written_.load(std::memory_order_relaxed);
    }
    uint64_t xrun_count() const noexcept {
        return xrun_count_.load(std::memory_order_relaxed);
    }
    uint64_t silence_frames_written() const noexcept {
        return silence_frames_written_.load(std::memory_order_relaxed);
    }

    void reset_counters() noexcept {
        frames_written_.store(0, std::memory_order_relaxed);
        xrun_count_.store(0, std::memory_order_relaxed);
        silence_frames_written_.store(0, std::memory_order_relaxed);
    }

    // ── Audio-thread entry point ────────────────────────────────────
    //
    // Zero-alloc, no blocking. Returns num_frames on success — the
    // output buffer is always fully populated (silence on underrun).
    //
    // @param out Interleaved float32 destination, size num_frames × channels.
    // @param num_frames Frame count requested by Oboe.
    // @param channels Interleaved channel count.
    size_t render(float* out, size_t num_frames, int channels) noexcept {
        if (!out || num_frames == 0 || channels <= 0) return 0;

        try {
            // Refresh config snapshot — non-blocking try_lock so the
            // audio thread never waits behind the control thread.
            {
                std::unique_lock<std::mutex> lock(
                    config_mutex_, std::try_to_lock);
                if (lock.owns_lock()) {
                    audio_snapshot_ = config_;
                }
                // On contention, audio_snapshot_ retains its previous value.
            }

            if (!ring_buffer_) {
                std::memset(out, 0, num_frames * channels * sizeof(float));
                silence_frames_written_.fetch_add(
                    num_frames, std::memory_order_relaxed);
                return num_frames;
            }

            const size_t frames_read = ring_buffer_->read(out, num_frames);

            // Volume ramp — first-order IIR over the read samples.
            const float target = audio_snapshot_.volume;
            float s = smoothed_volume_;
            const size_t samples = frames_read * static_cast<size_t>(channels);
            for (size_t i = 0; i < samples; ++i) {
                s += (target - s) * VOLUME_RAMP_COEFFICIENT;
                out[i] *= s;
            }
            smoothed_volume_ = s;

            if (frames_read < num_frames) {
                xrun_count_.fetch_add(1, std::memory_order_relaxed);
                // Always zero the unfilled tail. The buffer holds stale data
                // otherwise, and silence_on_underrun only controls whether
                // the silent frames are reported in the counters.
                const size_t missing_frames = num_frames - frames_read;
                std::memset(
                    out + frames_read * static_cast<size_t>(channels),
                    0,
                    missing_frames * static_cast<size_t>(channels) *
                        sizeof(float));
                if (audio_snapshot_.silence_on_underrun) {
                    silence_frames_written_.fetch_add(
                        missing_frames, std::memory_order_relaxed);
                }
            }

            frames_written_.fetch_add(frames_read, std::memory_order_relaxed);
            return num_frames;
        } catch (...) {
            // Unreachable in practice (ring_buffer_->read is noexcept,
            // memcpy/memset are noexcept, the ramp is pure arithmetic),
            // but rule 6 requires the guard. Fill silence and keep the
            // stream alive rather than propagating to Oboe.
            std::memset(out, 0, num_frames * channels * sizeof(float));
            return num_frames;
        }
    }

private:
    // First-order IIR coefficient: smoothed += (target - smoothed) × k.
    // 0.0005 → ~2000-sample (≈42 ms @ 48 kHz, counted per interleaved sample)
    // time constant. Fast enough to feel responsive, slow enough to never click.
    static constexpr float VOLUME_RAMP_COEFFICIENT = 0.0005f;

    struct Config {
        float volume = 1.0f;
        bool  silence_on_underrun = true;
    };

    LockFreeRingBuffer<float>* ring_buffer_;

    // Control-thread-owned config, protected by mutex per rule 7.
    Config config_;
    mutable std::mutex config_mutex_;

    // Audio-thread-local snapshot, refreshed each render() via try_lock.
    // Never touches a lock while actually rendering.
    Config audio_snapshot_;

    // Audio-thread-local volume smoother. Not shared — no mutex needed.
    float smoothed_volume_ = 1.0f;

    // Counters — rule 7 is satisfied by atomics for single-word values;
    // a mutex per increment would violate the zero-lock audio-path
    // requirement (see class header).
    std::atomic<uint64_t> frames_written_{0};
    std::atomic<uint64_t> xrun_count_{0};
    std::atomic<uint64_t> silence_frames_written_{0};
};

}  // namespace futura::engine::core

#endif  // FUTURA_ENGINE_CORE_ZERO_ALLOC_RENDER_LOOP_H