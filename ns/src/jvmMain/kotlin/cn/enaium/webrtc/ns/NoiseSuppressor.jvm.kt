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

package cn.enaium.webrtc.ns

// =========================================================================
// JNI bridge - loads the native library and provides external declarations
// =========================================================================

internal object Jni {
    init {
        NativeLoader.load()
    }

    external fun version(): String

    external fun suppressorCreate(sampleRateHz: Int, numChannels: Int, level: Int): Long
    external fun suppressorDestroy(ptr: Long)
    external fun suppressorAnalyze(ptr: Long, samples: FloatArray): Boolean
    external fun suppressorProcess(ptr: Long, samples: FloatArray): Boolean
    external fun suppressorSetCaptureOutputUsage(ptr: Long, captureOutputUsed: Boolean)
    external fun suppressorSampleRateHz(ptr: Long): Int
    external fun suppressorNumChannels(ptr: Long): Int
    external fun suppressorNumFramesPerChannel(ptr: Long): Int
}

// =========================================================================
// JVM/Android actual implementation
// =========================================================================

class JvmNoiseSuppressor internal constructor(private val ptr: Long) : NoiseSuppressor {

    private var closed = false

    private fun handle(): Long {
        check(!closed) { "NoiseSuppressor is closed" }
        return ptr
    }

    override val sampleRateHz: Int
        get() = Jni.suppressorSampleRateHz(handle())

    override val numChannels: Int
        get() = Jni.suppressorNumChannels(handle())

    override val numFramesPerChannel: Int
        get() = Jni.suppressorNumFramesPerChannel(handle())

    override fun analyze(interleaved: FloatArray) {
        val handle = handle()
        requireFrameSize(interleaved, numChannels, numFramesPerChannel)
        if (!Jni.suppressorAnalyze(handle, interleaved)) {
            error("webrtc_ns_suppressor_analyze rejected a frame of ${interleaved.size} samples")
        }
    }

    override fun process(interleaved: FloatArray) {
        val handle = handle()
        requireFrameSize(interleaved, numChannels, numFramesPerChannel)
        if (!Jni.suppressorProcess(handle, interleaved)) {
            error("webrtc_ns_suppressor_process rejected a frame of ${interleaved.size} samples")
        }
    }

    override fun setCaptureOutputUsage(captureOutputUsed: Boolean) {
        Jni.suppressorSetCaptureOutputUsage(handle(), captureOutputUsed)
    }

    override fun close() {
        if (closed) return
        closed = true
        Jni.suppressorDestroy(ptr)
    }
}

actual fun createNoiseSuppressor(
    sampleRateHz: Int,
    numChannels: Int,
    level: NsLevel,
): NoiseSuppressor {
    requireSuppressorArguments(sampleRateHz, numChannels)
    val ptr = Jni.suppressorCreate(sampleRateHz, numChannels, level.nativeValue)
    check(ptr != 0L) {
        "webrtc_ns_suppressor_create returned null for ${sampleRateHz}Hz/$numChannels channels"
    }
    return JvmNoiseSuppressor(ptr)
}

actual fun noiseSuppressorVersion(): String = Jni.version()
