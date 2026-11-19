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

@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package cn.enaium.webrtc.ns

import org.khronos.webgl.Float32Array
import org.khronos.webgl.get
import org.khronos.webgl.set

// =========================================================================
// JS glue declarations
//
// The globals are defined by webrtc_ns_kmp_glue.js in the
// @enaium/webrtc-ns-kmp npm package. The host has to await
// initWebrtcNsKmp() before calling into this module; the glue then owns the
// Emscripten module and marshals each frame through the wasm heap.
// =========================================================================

private external fun webrtcNsKmpCreate(sampleRateHz: Int, numChannels: Int, level: Int): Int
private external fun webrtcNsKmpDestroy(ptr: Int)
private external fun webrtcNsKmpAnalyze(ptr: Int, samples: Float32Array, numFrames: Int): Int
private external fun webrtcNsKmpProcess(ptr: Int, samples: Float32Array, numFrames: Int): Int
private external fun webrtcNsKmpSetCaptureOutputUsage(ptr: Int, captureOutputUsed: Boolean)
private external fun webrtcNsKmpSampleRateHz(ptr: Int): Int
private external fun webrtcNsKmpNumChannels(ptr: Int): Int
private external fun webrtcNsKmpNumFramesPerChannel(ptr: Int): Int
private external fun webrtcNsKmpVersion(): String?

// =========================================================================
// wasmJs actual implementation
// =========================================================================

class WasmNoiseSuppressor internal constructor(private val ptr: Int) : NoiseSuppressor {

    override val sampleRateHz: Int = webrtcNsKmpSampleRateHz(ptr)

    override val numChannels: Int = webrtcNsKmpNumChannels(ptr)

    override val numFramesPerChannel: Int = webrtcNsKmpNumFramesPerChannel(ptr)

    /**
     * Reusable bridge buffer: the JS glue reads and writes a Float32Array, so
     * the frame is copied in and (for process) back out instead of allocating
     * two typed arrays per 10 ms frame.
     */
    private val frame = Float32Array(numChannels * numFramesPerChannel)

    private var closed = false

    private fun handle(): Int {
        check(!closed) { "NoiseSuppressor is closed" }
        return ptr
    }

    override fun analyze(interleaved: FloatArray) {
        val suppressor = handle()
        requireFrameSize(interleaved, numChannels, numFramesPerChannel)
        for (i in interleaved.indices) frame[i] = interleaved[i]
        if (webrtcNsKmpAnalyze(suppressor, frame, numFramesPerChannel) == 0) {
            error("webrtc_ns_wasm_analyze rejected a frame of ${interleaved.size} samples")
        }
    }

    override fun process(interleaved: FloatArray) {
        val suppressor = handle()
        requireFrameSize(interleaved, numChannels, numFramesPerChannel)
        for (i in interleaved.indices) frame[i] = interleaved[i]
        if (webrtcNsKmpProcess(suppressor, frame, numFramesPerChannel) == 0) {
            error("webrtc_ns_wasm_process rejected a frame of ${interleaved.size} samples")
        }
        for (i in interleaved.indices) interleaved[i] = frame[i]
    }

    override fun setCaptureOutputUsage(captureOutputUsed: Boolean) {
        webrtcNsKmpSetCaptureOutputUsage(handle(), captureOutputUsed)
    }

    override fun close() {
        if (closed) return
        closed = true
        webrtcNsKmpDestroy(ptr)
    }
}

actual fun createNoiseSuppressor(
    sampleRateHz: Int,
    numChannels: Int,
    level: NsLevel,
): NoiseSuppressor {
    requireSuppressorArguments(sampleRateHz, numChannels)
    val ptr = webrtcNsKmpCreate(sampleRateHz, numChannels, level.nativeValue)
    check(ptr != 0) {
        "webrtc_ns_wasm_create returned null for ${sampleRateHz}Hz/$numChannels channels"
    }
    return WasmNoiseSuppressor(ptr)
}

actual fun noiseSuppressorVersion(): String = webrtcNsKmpVersion() ?: ""
