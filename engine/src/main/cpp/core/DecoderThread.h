// futura-audio/engine/src/main/cpp/core/DecoderThread.h
//
// Decoder thread — drives source → resampler → ring buffer pipeline.
// Sits between a DecoderSource (FFmpeg or FLAC, adapted) and the
// LockFreeRingBuffer that ZeroAllocRenderLoop drains.
//
// Pause/resume is for seek/flush sequencing, not battery. When paused,
// any in-flight frames read from the source are discarded (acceptable —
// the caller is about to seek or clear the ring).
//
// Growth notes:
//   • decode/MemoryMappedFile.h (tree) — will speed cold seeks by
//     mmap'ing local files instead of using FFmpeg's default read loop.
//   • decode/ResamplerEngine.h — no growth needed; already integrated.
//   • The ResamplerEngine's process() cannot be flushed at EOF (it
//     early-returns for in_frames == 0), so up to ~one block of
//     resampled tail is lost at track end. Acceptable; <10ms in practice.

#ifndef FUTURA_ENGINE_CORE_DECODER_THREAD_H
#define FUTURA_ENGINE_CORE_DECODER_THREAD_H

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "core/LockFreeRingBuffer.h"
#include "decode/ResamplerEngine.h"

namespace futura::engine::core {

// ── Abstract decoder source ────────────────────────────────────────────
//
// Any decoder exposing the pull API can be wrapped via
// DecoderSourceAdapter<T> below. FFmpegDecoderContext and
// FlacNativeDecoder both satisfy it.

class DecoderSource {
public:
    virtual ~DecoderSource() = default;
    virtual size_t read_frames(float* out, size_t max_frames) = 0;
    virtual bool seek_to(int64_t position_ms) = 0;
    virtual int32_t sample_rate() const = 0;
    virtual int32_t channels() const = 0;
    virtual int64_t duration_ms() const = 0;
    virtual const std::string& last_error() const = 0;
};

// ── Decoder thread ─────────────────────────────────────────────────────

class DecoderThread {
public:
    DecoderThread() noexcept = default;

    ~DecoderThread() { stop(); }

    DecoderThread(const DecoderThread&) = delete;
    DecoderThread& operator=(const DecoderThread&) = delete;
    DecoderThread(DecoderThread&&) = delete;
    DecoderThread& operator=(DecoderThread&&) = delete;

    // ── Lifecycle ──────────────────────────────────────────────────

    // Start the thread. Takes ownership of source and resampler.
    // Blocks until the thread is running. Returns false on failure.
    bool start(std::unique_ptr<DecoderSource> source,
               LockFreeRingBuffer<float>* ring,
               std::unique_ptr<decode::ResamplerEngine> resampler = nullptr) {
        try {
            if (!source || !ring) { set_error("start: null source or ring"); return false; }
            if (thread_.joinable()) { set_error("start: already running"); return false; }

            const int32_t ch = source->channels();
            if (ch <= 0) { set_error("start: invalid source channels"); return false; }
            if (ring->getChannels() != static_cast<size_t>(ch)) {
                set_error("start: ring/source channel mismatch");
                return false;
            }
            if (resampler && resampler->channels() != ch) {
                set_error("start: resampler/source channel mismatch");
                return false;
            }

            source_    = std::move(source);
            resampler_ = std::move(resampler);
            ring_      = ring;
            channels_  = ch;

            scratch_in_.resize(CHUNK_FRAMES * static_cast<size_t>(ch));
            // Max upsample ratio 4x + 256-frame headroom for src internal
            // buffering flushes.
            scratch_out_.resize(
                CHUNK_FRAMES * 4 * static_cast<size_t>(ch) + 256 * static_cast<size_t>(ch));

            stop_requested_.store(false);
            pause_requested_.store(false);
            pause_acknowledged_.store(false);
            ended_.store(false);
            thread_exited_.store(false);
            frames_decoded_.store(0);
            frames_written_.store(0);
            { std::lock_guard<std::mutex> lock(error_mutex_); last_error_.clear(); }

            thread_ = std::thread(&DecoderThread::run, this);
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("start: ") + e.what());
        } catch (...) {
            set_error("start: unknown exception");
        }
        return false;
    }

    // Stop and join. Idempotent. Blocks up to one read_frames call
    // (5–50ms typical). Cannot be called from the decoder thread itself.
    void stop() noexcept {
        try {
            stop_requested_.store(true);
            pause_requested_.store(false);
            if (thread_.joinable() &&
                thread_.get_id() != std::this_thread::get_id()) {
                thread_.join();
            }
            source_.reset();
            resampler_.reset();
            ring_ = nullptr;
        } catch (...) { /* noexcept */ }
    }

    // ── Pause / resume for seek/flush sequencing ───────────────────
    //
    // pause() blocks until the decoder thread has acknowledged that no
    // further ring writes are in flight. Safe to call ring->clear()
    // after pause() returns and before resume(). May lose up to one
    // chunk of in-flight decoded audio — the caller is about to seek
    // or flush anyway.

    void pause() noexcept {
        try {
            if (!thread_.joinable() || thread_exited_.load()) return;
            pause_requested_.store(true);
            while (!pause_acknowledged_.load() &&
                   !stop_requested_.load() &&
                   !thread_exited_.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
        } catch (...) { /* noexcept */ }
    }

    void resume() noexcept {
        try {
            pause_requested_.store(false);
            pause_acknowledged_.store(false);
        } catch (...) { /* noexcept */ }
    }

    // Forward seek to the underlying source. PRECONDITION: caller has
    // called pause() and it has returned. Returns false otherwise.
    bool seek_source(int64_t position_ms) noexcept {
        try {
            if (!source_) return false;
            if (!pause_acknowledged_.load()) {
                set_error("seek_source: must be paused first");
                return false;
            }
            const bool ok = source_->seek_to(position_ms);
            if (ok) ended_.store(false);
            return ok;
        } catch (const std::exception& e) {
            set_error(std::string("seek_source: ") + e.what());
        } catch (...) {
            set_error("seek_source: unknown exception");
        }
        return false;
    }

    // ── Status (safe from any thread) ──────────────────────────────

    bool is_running() const noexcept {
        return thread_.joinable() &&
               !thread_exited_.load() &&
               !stop_requested_.load();
    }
    bool has_ended() const noexcept { return ended_.load(); }
    int64_t frames_decoded() const noexcept { return frames_decoded_.load(); }
    int64_t frames_written() const noexcept { return frames_written_.load(); }
    int32_t channels() const noexcept { return channels_; }

    std::string last_error() const {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            return last_error_;
        } catch (...) { return {}; }
    }

private:
    static constexpr size_t CHUNK_FRAMES = 8192;

    void run() noexcept {
        try {
            while (!stop_requested_.load()) {
                // Pause handling
                if (pause_requested_.load()) {
                    while (pause_requested_.load() && !stop_requested_.load()) {
                        pause_acknowledged_.store(true);
                        std::this_thread::sleep_for(std::chrono::milliseconds(2));
                    }
                    pause_acknowledged_.store(false);
                    continue;
                }

                if (ended_.load()) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
                    continue;
                }

                const size_t free_frames = ring_->getFramesFree();
                if (free_frames == 0) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                    continue;
                }

                // Bound input by worst-case upsample ratio so output fits.
                size_t max_input = free_frames;
                if (resampler_ && !resampler_->is_passthrough()) {
                    const double ratio = resampler_->ratio();
                    if (ratio > 1.0) {
                        max_input = static_cast<size_t>(
                            static_cast<double>(free_frames) / ratio);
                    }
                }
                if (max_input == 0) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                    continue;
                }
                if (max_input > CHUNK_FRAMES) max_input = CHUNK_FRAMES;

                const size_t decoded = source_->read_frames(scratch_in_.data(), max_input);
                if (decoded == 0) {
                    // Zero frames means end of stream or a read error. Record
                    // the source's message if it has one, so an error is not
                    // silently reported as a normal end.
                    const std::string src_err = source_->last_error();
                    if (!src_err.empty()) set_error("source: " + src_err);
                    // GROWTH: ResamplerEngine has no flush() — a proper one
                    // would drain the resampler tail here. Loss <10ms.
                    ended_.store(true);
                    continue;
                }
                frames_decoded_.fetch_add(
                    static_cast<int64_t>(decoded), std::memory_order_relaxed);

                const float* data_ptr = scratch_in_.data();
                size_t data_frames = decoded;
                if (resampler_ && !resampler_->is_passthrough()) {
                    data_frames = resampler_->process(
                        scratch_in_.data(), decoded,
                        scratch_out_.data(),
                        scratch_out_.size() / static_cast<size_t>(channels_));
                    data_ptr = scratch_out_.data();
                }

                write_to_ring(data_ptr, data_frames);
            }
        } catch (const std::exception& e) {
            set_error(std::string("run: ") + e.what());
        } catch (...) {
            set_error("run: unknown exception");
        }
        thread_exited_.store(true);
    }

    void write_to_ring(const float* data, size_t frames) noexcept {
        size_t written = 0;
        while (written < frames) {
            if (stop_requested_.load() || pause_requested_.load()) return;
            const size_t w = ring_->write(
                data + written * static_cast<size_t>(channels_),
                frames - written);
            if (w == 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            } else {
                written += w;
                frames_written_.fetch_add(
                    static_cast<int64_t>(w), std::memory_order_relaxed);
            }
        }
    }

    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    std::unique_ptr<DecoderSource>                       source_;
    std::unique_ptr<decode::ResamplerEngine>             resampler_;
    LockFreeRingBuffer<float>*                           ring_ = nullptr;
    int32_t                                              channels_ = 0;

    std::vector<float>                                   scratch_in_;
    std::vector<float>                                   scratch_out_;

    std::thread                                          thread_;

    std::atomic<bool>                                    stop_requested_{false};
    std::atomic<bool>                                    pause_requested_{false};
    std::atomic<bool>                                    pause_acknowledged_{false};
    std::atomic<bool>                                    ended_{false};
    std::atomic<bool>                                    thread_exited_{false};
    std::atomic<int64_t>                                 frames_decoded_{0};
    std::atomic<int64_t>                                 frames_written_{0};

    mutable std::mutex                                   error_mutex_;
    std::string                                          last_error_;
};

// ── Adapter: wraps any concrete decoder as DecoderSource ───────────────

template <typename DecoderT>
class DecoderSourceAdapter : public DecoderSource {
public:
    explicit DecoderSourceAdapter(DecoderT& d) noexcept : decoder_(d) {}

    size_t read_frames(float* out, size_t max_frames) override {
        return decoder_.read_frames(out, max_frames);
    }
    bool seek_to(int64_t position_ms) override {
        return decoder_.seek_to(position_ms);
    }
    int32_t sample_rate() const override { return decoder_.sample_rate(); }
    int32_t channels() const override    { return decoder_.channels(); }
    int64_t duration_ms() const override { return decoder_.duration_ms(); }
    const std::string& last_error() const override {
        return decoder_.last_error();
    }

private:
    DecoderT& decoder_;
};

}  // namespace futura::engine::core

#endif  // FUTURA_ENGINE_CORE_DECODER_THREAD_H