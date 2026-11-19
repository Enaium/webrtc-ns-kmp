/*
 *  Copyright (c) 2026 The webrtc-ns-kmp project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

/*
 * Flat, handle-based wrapper around the NS C API for Kotlin/Wasm.
 *
 * Kotlin/Wasm has no cinterop and no raw memory access, so the C++ handle is
 * passed as a 32-bit integer. Samples stay a float* into the Emscripten heap;
 * the JS glue allocates the buffer, copies the Kotlin Float32Array into it,
 * calls these functions, and copies the processed frame back.
 */

#include "webrtc_ns_c.h"

#include <stdint.h>

int webrtc_ns_wasm_create(int sample_rate_hz, int num_channels, int level) {
  return (int)(intptr_t)webrtc_ns_suppressor_create(sample_rate_hz,
                                                    num_channels, level);
}

void webrtc_ns_wasm_destroy(int suppressor) {
  webrtc_ns_suppressor_destroy(
      (webrtc_ns_suppressor_t*)(intptr_t)suppressor);
}

int webrtc_ns_wasm_analyze(int suppressor,
                           const float* interleaved,
                           int num_frames) {
  return webrtc_ns_suppressor_analyze(
             (webrtc_ns_suppressor_t*)(intptr_t)suppressor, interleaved,
             num_frames)
             ? 1
             : 0;
}

int webrtc_ns_wasm_process(int suppressor,
                           float* interleaved,
                           int num_frames) {
  return webrtc_ns_suppressor_process(
             (webrtc_ns_suppressor_t*)(intptr_t)suppressor, interleaved,
             num_frames)
             ? 1
             : 0;
}

void webrtc_ns_wasm_set_capture_output_usage(int suppressor,
                                             int capture_output_used) {
  webrtc_ns_suppressor_set_capture_output_usage(
      (webrtc_ns_suppressor_t*)(intptr_t)suppressor,
      capture_output_used != 0);
}

int webrtc_ns_wasm_sample_rate_hz(int suppressor) {
  return webrtc_ns_suppressor_sample_rate_hz(
      (const webrtc_ns_suppressor_t*)(intptr_t)suppressor);
}

int webrtc_ns_wasm_num_channels(int suppressor) {
  return webrtc_ns_suppressor_num_channels(
      (const webrtc_ns_suppressor_t*)(intptr_t)suppressor);
}

int webrtc_ns_wasm_num_frames_per_channel(int suppressor) {
  return webrtc_ns_suppressor_num_frames_per_channel(
      (const webrtc_ns_suppressor_t*)(intptr_t)suppressor);
}

const char* webrtc_ns_wasm_version(void) {
  return webrtc_ns_version();
}
