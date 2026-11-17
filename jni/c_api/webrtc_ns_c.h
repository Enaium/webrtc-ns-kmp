/*
 *  Copyright (c) 2026 The webrtc-ns-kmp project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

#ifndef WEBRTC_NS_C_H_
#define WEBRTC_NS_C_H_

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle to a noise suppressor instance. */
typedef struct webrtc_ns_suppressor_t webrtc_ns_suppressor_t;

/*
 * Noise attenuation target, matching NsConfig::SuppressionLevel.
 * The values are part of the ABI: the Kotlin enum passes them as-is.
 */
enum webrtc_ns_level_t {
  WEBRTC_NS_LEVEL_6_DB = 0,
  WEBRTC_NS_LEVEL_12_DB = 1,
  WEBRTC_NS_LEVEL_18_DB = 2,
  WEBRTC_NS_LEVEL_21_DB = 3
};

const char* webrtc_ns_version(void);

/*
 * Creates a suppressor for one configuration.
 *
 * sample_rate_hz must be 16000, 32000 or 48000; num_channels must be in
 * [1, WEBRTC_NS_MAX_CHANNELS]. Returns NULL for anything else, so an invalid
 * call cannot abort the process on the library's internal check.
 *
 * Frames are 10 ms: num_frames_per_channel() is sample_rate_hz / 100.
 */
#define WEBRTC_NS_MAX_CHANNELS 32

webrtc_ns_suppressor_t* webrtc_ns_suppressor_create(int sample_rate_hz,
                                                    int num_channels,
                                                    int level);

void webrtc_ns_suppressor_destroy(webrtc_ns_suppressor_t* suppressor);

/*
 * Analyses one frame without modifying it. Must be called before process()
 * for the same frame.
 *
 * interleaved holds num_frames_per_channel() * num_channels() samples in
 * frame-major order: [frame0_ch0, frame0_ch1, frame1_ch0, ...], in the int16
 * range [-32768, 32767] - the range WebRTC's audio processing works in, not
 * [-1, 1].
 *
 * Returns false when interleaved is NULL or num_frames is not exactly one
 * 10 ms frame; the suppressor is then left untouched.
 */
bool webrtc_ns_suppressor_analyze(webrtc_ns_suppressor_t* suppressor,
                                  const float* interleaved,
                                  int num_frames);

/*
 * Applies noise suppression to one frame in place. Same layout and return
 * value as webrtc_ns_suppressor_analyze().
 */
bool webrtc_ns_suppressor_process(webrtc_ns_suppressor_t* suppressor,
                                  float* interleaved,
                                  int num_frames);

/*
 * Lets the suppressor skip output processing when the output is not going to
 * be used, for instance while muted. While it is false process() leaves the
 * buffer untouched.
 */
void webrtc_ns_suppressor_set_capture_output_usage(
    webrtc_ns_suppressor_t* suppressor, bool capture_output_used);

int webrtc_ns_suppressor_sample_rate_hz(const webrtc_ns_suppressor_t* suppressor);
int webrtc_ns_suppressor_num_channels(const webrtc_ns_suppressor_t* suppressor);
int webrtc_ns_suppressor_num_frames_per_channel(
    const webrtc_ns_suppressor_t* suppressor);

#ifdef __cplusplus
}
#endif

#endif /* WEBRTC_NS_C_H_ */
