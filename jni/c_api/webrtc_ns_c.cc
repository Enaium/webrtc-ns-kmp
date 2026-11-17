/*
 *  Copyright (c) 2026 The webrtc-ns-kmp project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

#include "webrtc_ns_c.h"

#include <cstddef>
#include <vector>

#include "api/noise_suppression.h"

namespace {

constexpr int kMaxChannels = WEBRTC_NS_MAX_CHANNELS;

webrtc::NoiseSuppression::Level ToLevel(int level) {
  switch (level) {
    case WEBRTC_NS_LEVEL_6_DB:
      return webrtc::NoiseSuppression::Level::k6dB;
    case WEBRTC_NS_LEVEL_18_DB:
      return webrtc::NoiseSuppression::Level::k18dB;
    case WEBRTC_NS_LEVEL_21_DB:
      return webrtc::NoiseSuppression::Level::k21dB;
    case WEBRTC_NS_LEVEL_12_DB:
    default:
      return webrtc::NoiseSuppression::Level::k12dB;
  }
}

bool IsSupportedSampleRate(int sample_rate_hz) {
  return sample_rate_hz == 16000 || sample_rate_hz == 32000 ||
         sample_rate_hz == 48000;
}

}  // namespace

struct webrtc_ns_suppressor_t {
  webrtc_ns_suppressor_t(int sample_rate_hz,
                         int num_channels,
                         webrtc::NoiseSuppression::Level level)
      : impl(sample_rate_hz,
             static_cast<size_t>(num_channels),
             webrtc::NoiseSuppression::Config{level}),
        sample_rate_hz(sample_rate_hz),
        num_channels(num_channels),
        num_frames_per_channel(sample_rate_hz / 100),
        // Only multi-channel frames are deinterleaved; the mono path hands the
        // caller's buffer to the library directly.
        planar(static_cast<size_t>(num_channels),
               std::vector<float>(static_cast<size_t>(num_frames_per_channel))),
        pointers(static_cast<size_t>(num_channels)) {
    for (int ch = 0; ch < num_channels; ++ch) {
      pointers[static_cast<size_t>(ch)] = planar[static_cast<size_t>(ch)].data();
    }
  }

  /** Interleaved -> per-channel scratch. */
  void Deinterleave(const float* interleaved) {
    for (int frame = 0; frame < num_frames_per_channel; ++frame) {
      const float* source = interleaved + frame * num_channels;
      for (int ch = 0; ch < num_channels; ++ch) {
        planar[static_cast<size_t>(ch)][static_cast<size_t>(frame)] = source[ch];
      }
    }
  }

  /** Per-channel scratch -> interleaved. */
  void Interleave(float* interleaved) const {
    for (int frame = 0; frame < num_frames_per_channel; ++frame) {
      float* destination = interleaved + frame * num_channels;
      for (int ch = 0; ch < num_channels; ++ch) {
        destination[ch] = planar[static_cast<size_t>(ch)][static_cast<size_t>(frame)];
      }
    }
  }

  webrtc::NoiseSuppression impl;
  const int sample_rate_hz;
  const int num_channels;
  const int num_frames_per_channel;
  std::vector<std::vector<float>> planar;
  std::vector<float*> pointers;
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
  if (suppressor->num_channels == 1) {
    const float* channels[1] = {interleaved};
    suppressor->impl.Analyze(channels);
    return true;
  }
  suppressor->Deinterleave(interleaved);
  suppressor->impl.Analyze(suppressor->pointers.data());
  return true;
}

bool webrtc_ns_suppressor_process(webrtc_ns_suppressor_t* suppressor,
                                  float* interleaved,
                                  int num_frames) {
  if (suppressor == nullptr || interleaved == nullptr ||
      num_frames != suppressor->num_frames_per_channel) {
    return false;
  }
  if (!suppressor->capture_output_used) {
    // Bypassed: the caller's frame already holds its own output.
    return true;
  }
  if (suppressor->num_channels == 1) {
    float* channels[1] = {interleaved};
    suppressor->impl.Process(channels);
    return true;
  }
  suppressor->Deinterleave(interleaved);
  suppressor->impl.Process(suppressor->pointers.data());
  suppressor->Interleave(interleaved);
  return true;
}

void webrtc_ns_suppressor_set_capture_output_usage(
    webrtc_ns_suppressor_t* suppressor, bool capture_output_used) {
  if (suppressor == nullptr) {
    return;
  }
  suppressor->capture_output_used = capture_output_used;
  suppressor->impl.SetCaptureOutputUsage(capture_output_used);
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
