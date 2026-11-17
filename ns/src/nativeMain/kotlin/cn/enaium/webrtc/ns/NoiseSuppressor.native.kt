/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.webrtc.ns

import kotlinx.cinterop.*
import webrtc_ns.*

// =========================================================================
// Native (cinterop) actual implementation
// =========================================================================

class NativeNoiseSuppressor internal constructor(
    private val ptr: CPointer<webrtc_ns_suppressor_t>,
) : NoiseSuppressor {

    private var closed = false

    private fun handle(): CPointer<webrtc_ns_suppressor_t> {
        check(!closed) { "NoiseSuppressor is closed" }
        return ptr
    }

    override val sampleRateHz: Int
        get() = webrtc_ns_suppressor_sample_rate_hz(handle())

    override val numChannels: Int
        get() = webrtc_ns_suppressor_num_channels(handle())

    override val numFramesPerChannel: Int
        get() = webrtc_ns_suppressor_num_frames_per_channel(handle())

    override fun analyze(interleaved: FloatArray) {
        val suppressor = handle()
        requireFrameSize(interleaved, numChannels, numFramesPerChannel)
        interleaved.usePinned { pinned ->
            if (!webrtc_ns_suppressor_analyze(suppressor, pinned.addressOf(0), numFramesPerChannel)) {
                error("webrtc_ns_suppressor_analyze rejected a frame of ${interleaved.size} samples")
            }
        }
    }

    override fun process(interleaved: FloatArray) {
        val suppressor = handle()
        requireFrameSize(interleaved, numChannels, numFramesPerChannel)
        interleaved.usePinned { pinned ->
            if (!webrtc_ns_suppressor_process(suppressor, pinned.addressOf(0), numFramesPerChannel)) {
                error("webrtc_ns_suppressor_process rejected a frame of ${interleaved.size} samples")
            }
        }
    }

    override fun setCaptureOutputUsage(captureOutputUsed: Boolean) {
        webrtc_ns_suppressor_set_capture_output_usage(handle(), captureOutputUsed)
    }

    override fun close() {
        if (closed) return
        closed = true
        webrtc_ns_suppressor_destroy(ptr)
    }
}

actual fun createNoiseSuppressor(
    sampleRateHz: Int,
    numChannels: Int,
    level: NsLevel,
): NoiseSuppressor {
    requireSuppressorArguments(sampleRateHz, numChannels)
    val ptr = webrtc_ns_suppressor_create(sampleRateHz, numChannels, level.nativeValue)
        ?: error("webrtc_ns_suppressor_create returned null for ${sampleRateHz}Hz/$numChannels channels")
    return NativeNoiseSuppressor(ptr)
}

actual fun noiseSuppressorVersion(): String = webrtc_ns_version()?.toKString() ?: ""
