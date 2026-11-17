/*
 *  Copyright (c) 2026 The webrtc-ns-kmp project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

#include <jni.h>

#include "webrtc_ns_c.h"

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_webrtc_ns_Jni_version(JNIEnv* env, jclass clazz) {
  return env->NewStringUTF(webrtc_ns_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorCreate(JNIEnv* env,
                                              jclass clazz,
                                              jint sample_rate_hz,
                                              jint num_channels,
                                              jint level) {
  return reinterpret_cast<jlong>(
      webrtc_ns_suppressor_create(sample_rate_hz, num_channels, level));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorDestroy(JNIEnv* env,
                                               jclass clazz,
                                               jlong ptr) {
  webrtc_ns_suppressor_destroy(
      reinterpret_cast<webrtc_ns_suppressor_t*>(ptr));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorAnalyze(JNIEnv* env,
                                               jclass clazz,
                                               jlong ptr,
                                               jfloatArray samples) {
  auto* suppressor = reinterpret_cast<webrtc_ns_suppressor_t*>(ptr);
  const jsize length = env->GetArrayLength(samples);
  const int frames =
      webrtc_ns_suppressor_num_frames_per_channel(suppressor);
  const int channels = webrtc_ns_suppressor_num_channels(suppressor);
  if (frames <= 0 || channels <= 0 || length != frames * channels) {
    return JNI_FALSE;
  }
  jfloat* data = env->GetFloatArrayElements(samples, nullptr);
  if (data == nullptr) {
    return JNI_FALSE;
  }
  const bool result =
      webrtc_ns_suppressor_analyze(suppressor, data, frames);
  // Analysis never writes to the frame.
  env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
  return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorProcess(JNIEnv* env,
                                               jclass clazz,
                                               jlong ptr,
                                               jfloatArray samples) {
  auto* suppressor = reinterpret_cast<webrtc_ns_suppressor_t*>(ptr);
  const jsize length = env->GetArrayLength(samples);
  const int frames =
      webrtc_ns_suppressor_num_frames_per_channel(suppressor);
  const int channels = webrtc_ns_suppressor_num_channels(suppressor);
  if (frames <= 0 || channels <= 0 || length != frames * channels) {
    return JNI_FALSE;
  }
  jfloat* data = env->GetFloatArrayElements(samples, nullptr);
  if (data == nullptr) {
    return JNI_FALSE;
  }
  const bool result = webrtc_ns_suppressor_process(suppressor, data, frames);
  env->ReleaseFloatArrayElements(samples, data, 0);
  return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorSetCaptureOutputUsage(
    JNIEnv* env,
    jclass clazz,
    jlong ptr,
    jboolean capture_output_used) {
  webrtc_ns_suppressor_set_capture_output_usage(
      reinterpret_cast<webrtc_ns_suppressor_t*>(ptr),
      capture_output_used != JNI_FALSE);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorSampleRateHz(JNIEnv* env,
                                                    jclass clazz,
                                                    jlong ptr) {
  return webrtc_ns_suppressor_sample_rate_hz(
      reinterpret_cast<webrtc_ns_suppressor_t*>(ptr));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorNumChannels(JNIEnv* env,
                                                   jclass clazz,
                                                   jlong ptr) {
  return webrtc_ns_suppressor_num_channels(
      reinterpret_cast<webrtc_ns_suppressor_t*>(ptr));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_ns_Jni_suppressorNumFramesPerChannel(JNIEnv* env,
                                                           jclass clazz,
                                                           jlong ptr) {
  return webrtc_ns_suppressor_num_frames_per_channel(
      reinterpret_cast<webrtc_ns_suppressor_t*>(ptr));
}
