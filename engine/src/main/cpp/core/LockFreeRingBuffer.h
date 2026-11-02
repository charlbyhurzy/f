// futura-audio/engine/src/main/cpp/core/LockFreeRingBuffer.h
//
// Phase 3 — SPSC lock-free ring buffer for PCM audio data. Feature #55.
//
// ── Responsibility ──────────────────────────────────────────────────────
// A bounded FIFO queue for interleaved PCM samples, safe for use between
// exactly TWO threads:
//
//   Producer  — the decoder thread, calling write() to publish decoded
//               frames.
//   Consumer  — the audio thread (AudioOutput::onAudioReady → Engine::
//               onAudioReady), calling read() to fetch frames for the
//               Oboe stream.
//
// No other thread may call write() or read(). getXxx() monitoring methods
// may be called from any thread (they read both indices and may observe a
// stale but never-corrupted snapshot).
//
// ── Why this specific design (Lamport's SPSC algorithm) ─────────────────
// There are three lock-free FIFO designs in common use:
//
//   1. Slot-sequence-number queues (Vyukov). Each slot carries an atomic
//      sequence. Correct, but adds 8 bytes per slot overhead and each
//      read/write is two atomic RMWs per slot — expensive for audio.
//
//   2. Monotonic-counter SPSC (Lamport, this file). Two atomic indices;
//      no per-slot atomics; each write is one atomic load + one atomic
//      store. The standard for real-time audio.
//
//   3. Spin-lock-protected queue. Not lock-free; wrong for audio.
//
// This file implements (2). It is the correct choice for the audio
// pipeline because:
//   • Zero heap allocations after construction.
//   • Zero locks (no priority inversion).
//   • Copy is done in at most two `memcpy` calls per batch (the
//     wraparound split), which is optimal for cache-friendly bulk
//     transfers.
//   • Byte-for-byte the pattern used by JUCE, PortAudio, WebRTC's
//     NetEq, and Android's own AudioTrack ring buffer.
//
// ── The key correctness invariant ──────────────────────────────────────
// Given unsigned integer arithmetic in C++ (well-defined modulo 2^N),
// the expression
//
//     (write_index_ - read_index_)
//
// always yields the count of frames currently in the buffer, REGARDLESS
// of whether either index has wrapped past `std::numeric_limits<size_t>::
// max()`. This is why the indices are monotonic (never reset to 0 by the
// buffer), and why subtraction — not comparison — is used for the count.
// Under-wrap works correctly for unsigned types.
//
// The producer never lets `write_index_ - read_index_ > capacity_frames_`
// (it reads `read_index_` before writing and truncates to fit). The
// consumer never lets `read_index_` exceed `write_index_` (it reads
// `write_index_` first and truncates). So the invariant
// `read_index_ <= write_index_ <= read_index_ + capacity_frames_` holds
// at all quiescent points, and it holds throughout under the release/
// acquire orderings below.
//
// ── Memory ordering proof (the important section) ──────────────────────
//
// Producer's write(data, n):
//   [A] read_index_.load(acquire)   ← ensures we see the consumer's
//                                     release-store of read_index_,
//                                     which happens AFTER the consumer
//                                     has finished reading the frames
//                                     we're about to overwrite.
//   [B] memcpy(buffer[write_pos..], data, n)  ← buffer writes
//   [C] write_index_.store(w + n, release)    ← publishes to consumer
//
// Consumer's read(out, n):
//   [D] write_index_.load(acquire)  ← synchronizes-with [C]. Ensures
//                                     buffer reads in [E] see the
//                                     producer's writes from [B].
//   [E] memcpy(out, buffer[read_pos..], n)    ← buffer reads
//   [F] read_index_.store(r + n, release)     ← publishes to producer
//
// The relevant synchronizes-with edges:
//   [C] --syncs-with--> [D]   (producer publishes data to consumer)
//   [F] --syncs-with--> [A]   (consumer publishes "frames freed" to producer)
//
// Both store/load pairs are release/acquire. Everything else is relaxed.
// The two own-index loads (producer's load of write_index_ at [A'] and
// consumer's load of read_index_ at [D']) are relaxed because only the
// owning thread writes those indices — no cross-thread concern.
//
// The getXxx() monitoring methods use acquire on both loads. This
// guarantees that if a consumer sees a large `write_index_` value, it
// sees all the corresponding buffer writes; and vice-versa. In practice
// monitoring methods are informational, so even relaxed would be safe,
// but acquire costs nothing extra on ARM64 and gives a stronger
// guarantee for stress tests.
//
// ── Power-of-two capacity ─────────────────────────────────────────────
// The buffer capacity is rounded up to the next power of two at
// construction. This allows storage indices to be computed with a bitmask
// (`index & mask_`) instead of a modulo, which is the difference between
// ~3 ns and ~15 ns per frame-batch memcpy on some ARM cores. For
// real-time audio, that matters.
//
// Memory cost of rounding up: worst case is 2x the requested size (e.g.,
// request 5000 frames → allocates 8192). Every caller should size with
// this in mind, but a 2-second buffer at 96kHz stereo → 192000 frames
// requested → 262144 (2^18) frames allocated → 2 MB. Acceptable.
//
// ── Zero-allocation promise ───────────────────────────────────────────
// Only the CONSTRUCTOR allocates (via std::vector::resize). Every other
// method is noexcept and allocates nothing. This is the promise the
// audio thread depends on; any change to this class must preserve it.
// std::vector is used rather than `new T[]` purely for RAII / exception
// safety in the constructor — after construction, no std::vector API is
// ever called.
//
// ── Type constraints ──────────────────────────────────────────────────
// T must be trivially copyable. Enforced with static_assert. For audio
// the concrete instantiation is `float` (matching AudioOutput.h's
// AudioFormat::Float) but the class is templated so a future
// Int16 ring buffer for the low-power offload path (feature #149) can
// reuse it.
//
// ── Threading summary ─────────────────────────────────────────────────
//   write()               — Producer thread ONLY
//   read()                — Consumer thread ONLY
//   getFramesAvailable()  — Any thread (may see slightly stale state)
//   getFramesFree()       — Any thread (may see slightly stale state)
//   getCapacityFrames()   — Any thread (immutable after construction)
//   getChannels()         — Any thread (immutable after construction)
//   getMemUsageBytes()    — Any thread (immutable after construction)
//   isEmpty() / isFull()  — Any thread (derived from getFramesXxx)
//   clear()               — Must not be called concurrently with
//                           write() or read(). Only safe during
//                           initialization or after playback has
//                           fully stopped.
//
// ── Integration with Engine.cpp ───────────────────────────────────────
// When this file is delivered, Engine::Impl gains:
//     std::unique_ptr<LockFreeRingBuffer<float>> ring_buffer_;
// and Engine::onAudioReady() changes from:
//     std::memset(audio_data, 0, total_bytes);   // silence
// to:
//     size_t read = ring_buffer_->read(
//         static_cast<float*>(audio_data), num_frames);
//     if (read < num_frames) {
//         // Underrun — fill remainder with silence.
//         std::memset(static_cast<float*>(audio_data)
//                         + read * channel_count,
//                     0, (num_frames - read) * channel_count * sizeof(float));
//         // Also notify xrun detector (future XRunDetector.h).
//     }
//
// Simultaneously, the decoder thread (once FFmpegDecoderContext.h lands)
// writes into ring_buffer_ between decode frames, and a real source
// produces continuous audio instead of the engine's current silence.
//
// ── Forward-reference audit ───────────────────────────────────────────
// Included headers (all stdlib, zero project headers):
//     <atomic>          — std::atomic<size_t>
//     <cstddef>         — size_t
//     <cstdint>         — uint8_t (for the enum class in this file)
//     <cstring>         — memcpy
//     <stdexcept>       — std::invalid_argument (constructor validation)
//     <type_traits>     — is_trivially_copyable
//     <vector>          — std::vector<T> for the backing store
//
// NOT included, NOT referenced (all exist in tree, all future):
//     core/NativeMemoryPool.h        — provides raw buffers; this class
//                                       uses std::vector instead so it has
//                                       no dependency on the memory pool
//     core/ZeroAllocRenderLoop.h     — the consumer of read(), not a
//                                       dependency of this class
//     oboe/XRunDetector.h            — reads getFramesAvailable() when
//                                       underrun occurs; not a dependency

#ifndef FUTURA_ENGINE_CORE_LOCK_FREE_RING_BUFFER_H
#define FUTURA_ENGINE_CORE_LOCK_FREE_RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace futura::engine::core {

// ═════════════════════════════════════════════════════════════════════════
// LockFreeRingBuffer
// ═════════════════════════════════════════════════════════════════════════

/**
 * Single-producer single-consumer lock-free ring buffer for interleaved
 * PCM samples.
 *
 * See the file header for the full design, correctness invariant,
 * memory-ordering proof, threading model, and integration notes.
 *
 * @tparam T Sample type. Must be trivially copyable. Concrete use is
 *         `float` for the standard audio path.
 */
template <typename T>
class LockFreeRingBuffer {
    static_assert(
        std::is_trivially_copyable<T>::value,
        "LockFreeRingBuffer<T> requires T to be trivially copyable "
        "(the batch copy path uses std::memcpy, which is only valid "
        "for trivially copyable types).");

public:

    // ═════════════════════════════════════════════════════════════════
    // Construction / destruction
    // ═════════════════════════════════════════════════════════════════

    /**
     * Construct a ring buffer.
     *
     * @param requested_frames Minimum number of frames the buffer should
     *        hold. The actual capacity is rounded up to the next power
     *        of two (see file header's "Power-of-two capacity" note).
     *        Must be > 0.
     * @param channels Number of interleaved channels per frame (1 =
     *        mono, 2 = stereo, 6 = 5.1). Must be > 0.
     *
     * @throws std::invalid_argument if `requested_frames == 0` or
     *         `channels == 0`.
     * @throws std::bad_alloc if the allocation of
     *         `capacity_frames_ * channels` samples fails.
     *
     * This is the ONLY method that allocates. All other methods are
     * noexcept and allocation-free — see the "Zero-allocation promise"
     * section in the file header.
     */
    LockFreeRingBuffer(size_t requested_frames, size_t channels)
        : channels_(channels) {

        if (requested_frames == 0) {
            throw std::invalid_argument(
                "LockFreeRingBuffer: requested_frames must be > 0");
        }
        if (channels == 0) {
            throw std::invalid_argument(
                "LockFreeRingBuffer: channels must be > 0");
        }

        // Round up to next power of two.
        size_t capacity = 1;
        while (capacity < requested_frames) {
            if (capacity > std::numeric_limits<size_t>::max() / 2) {
                throw std::invalid_argument(
                    "LockFreeRingBuffer: requested_frames is too large");
            }
            capacity <<= 1;
        }

        if (channels > std::numeric_limits<size_t>::max() / capacity) {
            throw std::invalid_argument(
                "LockFreeRingBuffer: frame and channel count overflow");
        }

        capacity_frames_  = capacity;
        capacity_samples_ = capacity * channels;
        mask_             = capacity - 1;

        // Allocate zero-initialized storage once. vector::resize is
        // exception-safe (strong guarantee) — on bad_alloc the
        // constructor unwinds cleanly and the object is never
        // constructed.
        buffer_.resize(capacity_samples_, T{});

        // Initialize indices to 0. The atomics start at their zero-
        // initialized value; this is technically redundant, but the
        // explicit stores make the initial state documented rather than
        // implied.
        write_index_.store(0, std::memory_order_relaxed);
        read_index_.store(0, std::memory_order_relaxed);
    }

    ~LockFreeRingBuffer() = default;

    // Non-copyable, non-movable. The ring buffer holds thread-local
    // state (the atomics) and a backing store whose pointer must remain
    // stable across the lifetime of the producer and consumer threads.
    // Copying would create two independent buffers that appear
    // connected but are not; moving would invalidate the storage
    // address under an in-flight memcpy.
    LockFreeRingBuffer(const LockFreeRingBuffer&) = delete;
    LockFreeRingBuffer& operator=(const LockFreeRingBuffer&) = delete;
    LockFreeRingBuffer(LockFreeRingBuffer&&) = delete;
    LockFreeRingBuffer& operator=(LockFreeRingBuffer&&) = delete;

    // ═════════════════════════════════════════════════════════════════
    // Producer API (call from ONE thread only)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Write up to `frames` frames from `data` into the buffer.
     *
     * If the buffer has less free space than `frames`, only the
     * available space is written and the return value is less than
     * `frames`. This is the standard "write what fits" contract for
     * audio FIFOs — the caller (decoder thread) can either block on a
     * condition variable, retry on the next decode cycle, or drop the
     * extra (which would be an overrun, and indicates the decoder is
     * faster than the audio thread can consume).
     *
     * @param data Interleaved sample data. Must be at least
     *        `frames * channels()` elements. Must not be null unless
     *        `frames == 0`.
     * @param frames Number of frames to attempt to write. May be 0.
     *
     * @return Actual number of frames written. `<= frames`.
     *
     * @note Producer thread ONLY. Calling from any other thread breaks
     *       the SPSC invariant.
     */
    size_t write(const T* data, size_t frames) noexcept {
        if (frames == 0 || data == nullptr) return 0;

        // [A] Acquire load of read_index_ — ensures that any buffer
        // reads the consumer finished (which are ordered-before its
        // release-store of read_index_) are visible to us before we
        // proceed to overwrite those slots.
        const size_t current_read =
            read_index_.load(std::memory_order_acquire);

        // [A'] Relaxed load of write_index_ — our own value; no other
        // thread writes it, so ordering is irrelevant.
        const size_t current_write =
            write_index_.load(std::memory_order_relaxed);

        // Unsigned subtraction gives the correct count across wraparound
        // (see file header's "key correctness invariant" section).
        const size_t used = current_write - current_read;
        const size_t free_frames = capacity_frames_ - used;
        const size_t to_write = (frames < free_frames) ? frames : free_frames;

        if (to_write == 0) return 0;

        // Storage position — bitmask instead of modulo.
        const size_t write_pos = current_write & mask_;

        // First chunk: from write_pos to end of the physical buffer.
        const size_t first_chunk_frames =
            (to_write < (capacity_frames_ - write_pos))
                ? to_write
                : (capacity_frames_ - write_pos);
        const size_t second_chunk_frames = to_write - first_chunk_frames;

        // [B] Buffer writes.
        std::memcpy(
            buffer_.data() + write_pos * channels_,
            data,
            first_chunk_frames * channels_ * sizeof(T));

        if (second_chunk_frames > 0) {
            std::memcpy(
                buffer_.data(),
                data + first_chunk_frames * channels_,
                second_chunk_frames * channels_ * sizeof(T));
        }

        // [C] Release store of write_index_ — publishes the buffer
        // writes above to the consumer's [D] acquire load.
        write_index_.store(
            current_write + to_write, std::memory_order_release);

        return to_write;
    }

    // ═════════════════════════════════════════════════════════════════
    // Consumer API (call from ONE thread only)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Read up to `frames` frames from the buffer into `out`.
     *
     * If fewer than `frames` frames are available, only the available
     * frames are read and the return value is less than `frames`. This
     * is the audio-thread's normal underrun path: fill the returned
     * `read_frames` slots from the buffer, then memset the remaining
     * `frames - read_frames` slots to silence and report the underrun
     * to the xrun detector.
     *
     * @param out Destination buffer. Must be at least
     *        `frames * channels()` elements. Must not be null unless
     *        `frames == 0`.
     * @param frames Number of frames to attempt to read. May be 0.
     *
     * @return Actual number of frames read. `<= frames`.
     *
     * @note Consumer thread ONLY. Calling from any other thread breaks
     *       the SPSC invariant.
     */
    size_t read(T* out, size_t frames) noexcept {
        if (frames == 0 || out == nullptr) return 0;

        // [D] Acquire load of write_index_ — synchronizes-with the
        // producer's [C] release-store. Guarantees that the buffer
        // reads in [E] see everything the producer wrote in [B].
        const size_t current_write =
            write_index_.load(std::memory_order_acquire);

        // [D'] Relaxed load of read_index_ — our own value.
        const size_t current_read =
            read_index_.load(std::memory_order_relaxed);

        const size_t available = current_write - current_read;
        const size_t to_read = (frames < available) ? frames : available;

        if (to_read == 0) return 0;

        const size_t read_pos = current_read & mask_;

        const size_t first_chunk_frames =
            (to_read < (capacity_frames_ - read_pos))
                ? to_read
                : (capacity_frames_ - read_pos);
        const size_t second_chunk_frames = to_read - first_chunk_frames;

        // [E] Buffer reads.
        std::memcpy(
            out,
            buffer_.data() + read_pos * channels_,
            first_chunk_frames * channels_ * sizeof(T));

        if (second_chunk_frames > 0) {
            std::memcpy(
                out + first_chunk_frames * channels_,
                buffer_.data(),
                second_chunk_frames * channels_ * sizeof(T));
        }

        // [F] Release store of read_index_ — publishes to the producer
        // (via its [A] acquire load) that these slots are now free.
        read_index_.store(
            current_read + to_read, std::memory_order_release);

        return to_read;
    }

    // ═════════════════════════════════════════════════════════════════
    // Monitoring (safe from any thread — informational, may be stale)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Frames currently in the buffer (unread). May be slightly stale if
     * called from a third thread while producer or consumer is active.
     * Guaranteed to be in `[0, capacity_frames()]`.
     */
    size_t getFramesAvailable() const noexcept {
        const size_t w = write_index_.load(std::memory_order_acquire);
        const size_t r = read_index_.load(std::memory_order_acquire);
        return w - r;
    }

    /**
     * Frames of free space in the buffer. May be slightly stale. Equals
     * `capacity_frames() - getFramesAvailable()`.
     */
    size_t getFramesFree() const noexcept {
        const size_t w = write_index_.load(std::memory_order_acquire);
        const size_t r = read_index_.load(std::memory_order_acquire);
        return capacity_frames_ - (w - r);
    }

    /**
     * Capacity of the buffer in frames (the rounded-up power of two).
     * Immutable after construction.
     */
    size_t getCapacityFrames() const noexcept {
        return capacity_frames_;
    }

    /**
     * Number of interleaved channels. Immutable after construction.
     */
    size_t getChannels() const noexcept {
        return channels_;
    }

    /**
     * Bytes allocated for the backing store. Useful for
     * `NativeEngine.getResidentMemoryBytes()` / memory profiling once
     * that growth path is wired. Immutable after construction.
     */
    size_t getMemUsageBytes() const noexcept {
        return capacity_samples_ * sizeof(T);
    }

    /**
     * True if no frames are currently available to read.
     */
    bool isEmpty() const noexcept {
        return getFramesAvailable() == 0;
    }

    /**
     * True if the buffer has zero free space.
     */
    bool isFull() const noexcept {
        return getFramesFree() == 0;
    }

    // ═════════════════════════════════════════════════════════════════
    // Reset
    // ═════════════════════════════════════════════════════════════════

    /**
     * Discard all buffered frames and reset both indices to 0.
     *
     * **Must not be called concurrently with write() or read().** Only
     * safe during construction, between playback sessions (after the
     * audio stream has been fully stopped and joined), or during
     * Engine::reset() when the caller has guaranteed no producer or
     * consumer is active.
     *
     * This is not a "clear under load" primitive — the SPSC guarantee
     * does not extend to a third writer of the indices. Callers in a
     * real-time context must use a different mechanism (e.g.,
     * discarding the buffer entirely and constructing a fresh one on
     * the next prepareSource).
     */
    void clear() noexcept {
        read_index_.store(0, std::memory_order_relaxed);
        write_index_.store(0, std::memory_order_relaxed);
    }

private:

    /**
     * Backing storage for `capacity_frames_ * channels_` interleaved
     * samples. Sized exactly once in the constructor; never resized,
     * never reallocated. std::vector is chosen over std::array solely
     * because the size is runtime-configurable — once allocated, this
     * class uses vector's `.data()` pointer only and never calls any
     * vector method that could invalidate it or allocate.
     */
    std::vector<T> buffer_;

    /** Rounded-up power-of-two capacity in frames. */
    size_t capacity_frames_ = 0;

    /** capacity_frames_ * channels_ — total element count. */
    size_t capacity_samples_ = 0;

    /** capacity_frames_ - 1 — used for `index & mask_` wraparound. */
    size_t mask_ = 0;

    /** Channels per frame (1 = mono, 2 = stereo, etc.). */
    size_t channels_ = 0;

    /**
     * Monotonic write index — the total number of frames ever written.
     * Wraps naturally on overflow; see the "key correctness invariant"
     * section in the file header.
     */
    std::atomic<size_t> write_index_{0};

    /**
     * Monotonic read index — the total number of frames ever read.
     * Wraps naturally on overflow; see above.
     */
    std::atomic<size_t> read_index_{0};
};

}  // namespace futura::engine::core

#endif  // FUTURA_ENGINE_CORE_LOCK_FREE_RING_BUFFER_H