/*
 *  Copyright (c) 2026 The webrtc-ns-kmp project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

#include "webrtc_ns_c.h"

#include <cstddef>

#include "audio_processing/audio_buffer.h"
#include "audio_processing/ns/noise_suppressor.h"
#include "audio_processing/ns/ns_config.h"

namespace {

constexpr int kMaxChannels = WEBRTC_NS_MAX_CHANNELS;

webrtc::NsConfig::SuppressionLevel ToLevel(int level) {
  switch (level) {
    case WEBRTC_NS_LEVEL_6_DB:
      return webrtc::NsConfig::SuppressionLevel::k6dB;
    case WEBRTC_NS_LEVEL_18_DB:
      return webrtc::NsConfig::SuppressionLevel::k18dB;
    case WEBRTC_NS_LEVEL_21_DB:
      return webrtc::NsConfig::SuppressionLevel::k21dB;
    case WEBRTC_NS_LEVEL_12_DB:
    default:
      return webrtc::NsConfig::SuppressionLevel::k12dB;
  }
}

bool IsSupportedSampleRate(int sample_rate_hz) {
  return sample_rate_hz == 16000 || sample_rate_hz == 32000 ||
         sample_rate_hz == 48000;
}

}  // namespace

// This wrapper drives NoiseSuppressor and AudioBuffer directly instead of the
// `webrtc::NoiseSuppression` convenience class the extraction ships. That class
// calls AudioBuffer::SplitIntoFrequencyBands() once in Analyze() and once in
// Process(), but the analysis filter bank has state: at 32/48 kHz every frame
// would advance it twice while the signal is only merged once, which decorrelates
// the bands the suppressor analyses from the ones it processes and leaves
// broadband artefacts ("static") in the output. Two AudioBuffers - one for the
// analysed frame, one for the processed frame - keep each filter bank advancing
// once per 10 ms frame, so an unmodified frame analyses and processes the same
// bands.
struct webrtc_ns_suppressor_t {
  webrtc_ns_suppressor_t(int sample_rate_hz,
                         int num_channels,
                         webrtc::NsConfig::SuppressionLevel level)
      : analysis_audio_(static_cast<size_t>(sample_rate_hz),
                        static_cast<size_t>(num_channels),
                        static_cast<size_t>(sample_rate_hz),
                        static_cast<size_t>(num_channels),
                        static_cast<size_t>(sample_rate_hz),
                        static_cast<size_t>(num_channels)),
        process_audio_(static_cast<size_t>(sample_rate_hz),
                       static_cast<size_t>(num_channels),
                       static_cast<size_t>(sample_rate_hz),
                       static_cast<size_t>(num_channels),
                       static_cast<size_t>(sample_rate_hz),
                       static_cast<size_t>(num_channels)),
        impl_(MakeConfig(level),
              static_cast<size_t>(sample_rate_hz),
              static_cast<size_t>(num_channels)),
        sample_rate_hz(sample_rate_hz),
        num_channels(num_channels),
        num_frames_per_channel(sample_rate_hz / 100) {}

  static webrtc::NsConfig MakeConfig(
      webrtc::NsConfig::SuppressionLevel level) {
    webrtc::NsConfig config;
    config.target_level = level;
    return config;
  }

  /** Interleaved -> the buffer's per-channel full-band storage. */
  void CopyIn(webrtc::AudioBuffer& audio, const float* interleaved) const {
    float* const* channels = audio.channels();
    for (int frame = 0; frame < num_frames_per_channel; ++frame) {
      const float* source = interleaved + frame * num_channels;
      for (int ch = 0; ch < num_channels; ++ch) {
        channels[ch][frame] = source[ch];
      }
    }
  }

  /** The buffer's per-channel full-band storage -> interleaved. */
  void CopyOut(const webrtc::AudioBuffer& audio, float* interleaved) const {
    const float* const* channels = audio.channels_const();
    for (int frame = 0; frame < num_frames_per_channel; ++frame) {
      float* destination = interleaved + frame * num_channels;
      for (int ch = 0; ch < num_channels; ++ch) {
        destination[ch] = channels[ch][frame];
      }
    }
  }

  // The two banks of 32/48 kHz analysis filters. Both see exactly one frame per
  // call, so neither is advanced twice for the same frame.
  webrtc::AudioBuffer analysis_audio_;
  webrtc::AudioBuffer process_audio_;
  webrtc::NoiseSuppressor impl_;
  const int sample_rate_hz;
  const int num_channels;
  const int num_frames_per_channel;
  bool capture_output_used = true;
};

const char* webrtc_ns_version(void) {
  return "WebRTC NS 1.0.0";
}

webrtc_ns_suppressor_t* webrtc_ns_suppressor_create(int sample_rate_hz,
                                                    int num_channels,
                                                    int level) {
  if (!IsSupportedSampleRate(sample_rate_hz) || num_channels < 1 ||
      num_channels > kMaxChannels) {
    return nullptr;
  }
  return new webrtc_ns_suppressor_t(sample_rate_hz, num_channels,
                                    ToLevel(level));
}

void webrtc_ns_suppressor_destroy(webrtc_ns_suppressor_t* suppressor) {
  delete suppressor;
}

bool webrtc_ns_suppressor_analyze(webrtc_ns_suppressor_t* suppressor,
                                  const float* interleaved,
                                  int num_frames) {
  if (suppressor == nullptr || interleaved == nullptr ||
      num_frames != suppressor->num_frames_per_channel) {
    return false;
  }
  suppressor->CopyIn(suppressor->analysis_audio_, interleaved);
  suppressor->analysis_audio_.SplitIntoFrequencyBands();
  suppressor->impl_.Analyze(suppressor->analysis_audio_);
  return true;
}

bool webrtc_ns_suppressor_process(webrtc_ns_suppressor_t* suppressor,
                                  float* interleaved,
                                  int num_frames) {
  if (suppressor == nullptr || interleaved == nullptr ||
      num_frames != suppressor->num_frames_per_channel) {
    return false;
  }
  suppressor->CopyIn(suppressor->process_audio_, interleaved);
  suppressor->process_audio_.SplitIntoFrequencyBands();
  const bool output_used = suppressor->capture_output_used;
  // Process updates the suppressor's state before it decides whether the
  // output is wanted, so it is called even while muted; only the synthesis and
  // the copy back are skipped then.
  suppressor->impl_.Process(&suppressor->process_audio_);
  if (!output_used) {
    // Bypassed: the caller's frame already holds its own output.
    return true;
  }
  suppressor->process_audio_.MergeFrequencyBands();
  suppressor->CopyOut(suppressor->process_audio_, interleaved);
  return true;
}

void webrtc_ns_suppressor_set_capture_output_usage(
    webrtc_ns_suppressor_t* suppressor, bool capture_output_used) {
  if (suppressor == nullptr) {
    return;
  }
  suppressor->capture_output_used = capture_output_used;
  suppressor->impl_.SetCaptureOutputUsage(capture_output_used);
}

int webrtc_ns_suppressor_sample_rate_hz(
    const webrtc_ns_suppressor_t* suppressor) {
  return suppressor == nullptr ? 0 : suppressor->sample_rate_hz;
}

int webrtc_ns_suppressor_num_channels(
    const webrtc_ns_suppressor_t* suppressor) {
  return suppressor == nullptr ? 0 : suppressor->num_channels;
}

int webrtc_ns_suppressor_num_frames_per_channel(
    const webrtc_ns_suppressor_t* suppressor) {
  return suppressor == nullptr ? 0 : suppressor->num_frames_per_channel;
}
