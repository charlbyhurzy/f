// futura-audio/engine/src/main/cpp/decode/FFmpegDecoderContext.h
//
// Phase 3 — FFmpeg decoder wrapper (feature #57). RAII around
// libavformat + libavcodec + libswresample. Decodes any container
// FFmpeg supports to interleaved float32.
//
// Requires FFmpeg 5.1 or newer (uses AVChannelLayout / ch_layout).
//
// Growth note: decode/MemoryMappedFile.h (tree) will provide mmap-backed
// fast seeking. Current impl uses FFmpeg's default file I/O — correct,
// just not optimally fast on cold seeks.

#ifndef FUTURA_ENGINE_DECODE_FFMPEG_DECODER_CONTEXT_H
#define FUTURA_ENGINE_DECODE_FFMPEG_DECODER_CONTEXT_H

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace futura::engine::decode {

// Not thread-safe. Use from one decoder thread only; guard it with a mutex
// if any other thread needs to call open, seek_to or read_frames.
class FFmpegDecoderContext {
public:
    FFmpegDecoderContext() = default;
    ~FFmpegDecoderContext() { close(); }

    FFmpegDecoderContext(const FFmpegDecoderContext&) = delete;
    FFmpegDecoderContext& operator=(const FFmpegDecoderContext&) = delete;
    FFmpegDecoderContext(FFmpegDecoderContext&&) = delete;
    FFmpegDecoderContext& operator=(FFmpegDecoderContext&&) = delete;

    bool open(const std::string& uri) {
        close();
        try {
            return open_impl(uri);
        } catch (const std::bad_alloc&) {
            set_error("out of memory during open");
        } catch (const std::exception& e) {
            set_error(std::string("exception during open: ") + e.what());
        } catch (...) {
            set_error("unknown exception during open");
        }
        close();
        return false;
    }

    void close() noexcept {
        if (swr_ctx_owned_)   { swr_free(&swr_ctx_);             swr_ctx_owned_ = false; }
        if (packet_)          { av_packet_free(&packet_);                                }
        if (frame_)           { av_frame_free(&frame_);                                  }
        if (codec_ctx_owned_) { avcodec_free_context(&codec_ctx_); codec_ctx_owned_ = false; }
        if (format_ctx_owned_){ avformat_close_input(&format_ctx_); format_ctx_owned_ = false; }

        audio_stream_index_ = -1;
        source_sample_rate_ = 0;
        source_channels_    = 0;
        source_bit_depth_   = 0;
        duration_ms_        = -1;
        format_name_.clear();
    }

    // Returns frames written (0 at EOF). Blocks only on file I/O.
    size_t read_frames(float* out, size_t max_frames) {
        if (!codec_ctx_ || !frame_ || !packet_ || !swr_ctx_ || !out) return 0;
        if (max_frames == 0 || source_channels_ <= 0) return 0;
        try {
            return read_frames_impl(out, max_frames);
        } catch (const std::bad_alloc&) {
            set_error("out of memory during decode");
        } catch (const std::exception& e) {
            set_error(std::string("exception during decode: ") + e.what());
        } catch (...) {
            set_error("unknown exception during decode");
        }
        return 0;
    }

    bool seek_to(int64_t position_ms) {
        if (!format_ctx_ || audio_stream_index_ < 0) return false;
        try {
            AVStream* stream = format_ctx_->streams[audio_stream_index_];
            int64_t target_ts = av_rescale_q(position_ms, AVRational{1, 1000},
                                             stream->time_base);
            if (av_seek_frame(format_ctx_, audio_stream_index_, target_ts,
                              AVSEEK_FLAG_BACKWARD) < 0) {
                set_error("av_seek_frame failed");
                return false;
            }
            avcodec_flush_buffers(codec_ctx_);
            av_frame_unref(frame_);
            frame_offset_ = 0;
            swr_convert(swr_ctx_, nullptr, 0, nullptr, 0);
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("exception during seek: ") + e.what());
            return false;
        } catch (...) {
            set_error("unknown exception during seek");
            return false;
        }
    }

    bool is_open()             const noexcept { return codec_ctx_ != nullptr; }
    int32_t sample_rate()      const noexcept { return source_sample_rate_; }
    int32_t channels()         const noexcept { return source_channels_; }
    int32_t bit_depth()        const noexcept { return source_bit_depth_; }
    int64_t duration_ms()      const noexcept { return duration_ms_; }
    const std::string& format_name() const noexcept { return format_name_; }
    const std::string& last_error()  const noexcept { return last_error_; }

private:
    bool open_impl(const std::string& uri) {
        if (avformat_open_input(&format_ctx_, uri.c_str(), nullptr, nullptr) < 0) {
            set_error("avformat_open_input failed for '" + uri + "'");
            return false;
        }
        format_ctx_owned_ = true;

        if (avformat_find_stream_info(format_ctx_, nullptr) < 0) {
            set_error("avformat_find_stream_info failed");
            return false;
        }

        audio_stream_index_ = av_find_best_stream(
            format_ctx_, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (audio_stream_index_ < 0) {
            set_error("no audio stream found");
            return false;
        }

        AVStream* stream = format_ctx_->streams[audio_stream_index_];
        const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
        if (!codec) {
            set_error("no decoder for codec id " +
                      std::to_string(stream->codecpar->codec_id));
            return false;
        }

        codec_ctx_ = avcodec_alloc_context3(codec);
        if (!codec_ctx_) { set_error("avcodec_alloc_context3 failed"); return false; }
        codec_ctx_owned_ = true;

        if (avcodec_parameters_to_context(codec_ctx_, stream->codecpar) < 0) {
            set_error("avcodec_parameters_to_context failed");
            return false;
        }

        codec_ctx_->request_sample_fmt = AV_SAMPLE_FMT_FLT;

        if (avcodec_open2(codec_ctx_, codec, nullptr) < 0) {
            set_error("avcodec_open2 failed");
            return false;
        }

        packet_ = av_packet_alloc();
        frame_  = av_frame_alloc();
        if (!packet_ || !frame_) { set_error("av_packet/frame_alloc failed"); return false; }

        source_sample_rate_ = codec_ctx_->sample_rate;
        source_channels_    = codec_ctx_->ch_layout.nb_channels;
        source_bit_depth_   = codec_ctx_->bits_per_raw_sample > 0
                            ? codec_ctx_->bits_per_raw_sample : 16;
        format_name_        = codec->name ? codec->name : "unknown";

        if (stream->duration != AV_NOPTS_VALUE) {
            duration_ms_ = av_rescale_q(stream->duration, stream->time_base,
                                        AVRational{1, 1000});
        } else if (format_ctx_->duration != AV_NOPTS_VALUE) {
            duration_ms_ = format_ctx_->duration / 1000;
        } else {
            duration_ms_ = -1;
        }

        AVChannelLayout out_layout;
        av_channel_layout_default(&out_layout, source_channels_);
        int swr_ret = swr_alloc_set_opts2(
            &swr_ctx_,
            &out_layout, AV_SAMPLE_FMT_FLT, source_sample_rate_,
            &codec_ctx_->ch_layout, codec_ctx_->sample_fmt, source_sample_rate_,
            0, nullptr);
        av_channel_layout_uninit(&out_layout);

        if (swr_ret < 0 || !swr_ctx_) { set_error("swr_alloc_set_opts2 failed"); return false; }
        swr_ctx_owned_ = true;

        if (swr_init(swr_ctx_) < 0) { set_error("swr_init failed"); return false; }
        return true;
    }

    size_t read_frames_impl(float* out, size_t max_frames) {
        size_t frames_written = 0;
        while (frames_written < max_frames) {
            const int available = frame_->nb_samples - frame_offset_;
            if (available > 0) {
                const size_t to_copy = std::min<size_t>(
                    static_cast<size_t>(available), max_frames - frames_written);

                // Build input pointers, honouring planar vs packed layout.
                // Planar: each data[ch] holds nb_samples for channel ch;
                //   advance every plane by frame_offset_ samples.
                // Packed: only data[0] is valid; it holds nb_samples × channels
                //   interleaved samples; advance by frame_offset_ × channels.
                const AVSampleFormat fmt =
                    static_cast<AVSampleFormat>(frame_->format);
                const int bytes_per_sample = av_get_bytes_per_sample(fmt);
                const int in_channels = frame_->ch_layout.nb_channels;
                const uint8_t* in_ptrs[8] = {nullptr};
                if (av_sample_fmt_is_planar(fmt)) {
                    for (int ch = 0; ch < in_channels && ch < 8; ++ch) {
                        in_ptrs[ch] = frame_->data[ch] +
                            static_cast<ptrdiff_t>(frame_offset_) * bytes_per_sample;
                    }
                } else {
                    in_ptrs[0] = frame_->data[0] +
                        static_cast<ptrdiff_t>(frame_offset_) *
                        bytes_per_sample * in_channels;
                }

                uint8_t* out_ptr = reinterpret_cast<uint8_t*>(out) +
                    frames_written * source_channels_ * sizeof(float);

                const int converted = swr_convert(
                    swr_ctx_, &out_ptr, static_cast<int>(to_copy),
                    in_ptrs, static_cast<int>(to_copy));
                if (converted < 0) { set_error("swr_convert failed"); return frames_written; }

                // swr_convert consumes all to_copy input frames we pass it (the
                // rate is unchanged), so advance the input cursor by to_copy, not
                // by the output count.
                frames_written += static_cast<size_t>(converted);
                frame_offset_  += static_cast<int>(to_copy);

                if (frame_offset_ >= frame_->nb_samples) {
                    av_frame_unref(frame_);
                    frame_offset_ = 0;
                }
                continue;
            }

            const int recv = avcodec_receive_frame(codec_ctx_, frame_);
            if (recv == 0) { frame_offset_ = 0; continue; }
            if (recv == AVERROR_EOF) return frames_written;
            if (recv == AVERROR(EAGAIN)) {
                const int read_ret = av_read_frame(format_ctx_, packet_);
                if (read_ret < 0) {
                    avcodec_send_packet(codec_ctx_, nullptr);
                    const int flush_recv = avcodec_receive_frame(codec_ctx_, frame_);
                    if (flush_recv < 0) return frames_written;
                    frame_offset_ = 0;
                    continue;
                }
                if (packet_->stream_index != audio_stream_index_) {
                    av_packet_unref(packet_);
                    continue;
                }
                const int send_ret = avcodec_send_packet(codec_ctx_, packet_);
                av_packet_unref(packet_);
                if (send_ret < 0 && send_ret != AVERROR(EAGAIN)) {
                    set_error("avcodec_send_packet failed");
                    return frames_written;
                }
                continue;
            }
            set_error("avcodec_receive_frame failed");
            return frames_written;
        }
        return frames_written;
    }

    void set_error(const std::string& msg) {
        try { last_error_ = msg; } catch (...) { /* keep previous message */ }
    }

    AVFormatContext* format_ctx_       = nullptr;
    bool             format_ctx_owned_ = false;
    AVCodecContext*  codec_ctx_        = nullptr;
    bool             codec_ctx_owned_  = false;
    SwrContext*      swr_ctx_          = nullptr;
    bool             swr_ctx_owned_    = false;
    AVPacket*        packet_           = nullptr;
    AVFrame*         frame_            = nullptr;
    int              frame_offset_     = 0;
    int              audio_stream_index_ = -1;

    int32_t     source_sample_rate_ = 0;
    int32_t     source_channels_    = 0;
    int32_t     source_bit_depth_   = 0;
    int64_t     duration_ms_        = -1;
    std::string format_name_;
    std::string last_error_;
};

}  // namespace futura::engine::decode

#endif  // FUTURA_ENGINE_DECODE_FFMPEG_DECODER_CONTEXT_H