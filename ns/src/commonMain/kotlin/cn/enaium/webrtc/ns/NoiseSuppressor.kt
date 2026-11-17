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

/**
 * Noise attenuation target of the suppressor.
 *
 * The names mirror WebRTC's `NsConfig::SuppressionLevel`: a higher level removes
 * more noise and also more of the low-level detail of the speech.
 */
enum class NsLevel(internal val nativeValue: Int) {
    DB6(0),
    DB12(1),
    DB18(2),
    DB21(3),
}

/**
 * A WebRTC noise suppressor instance.
 *
 * A suppressor is built for one sample rate and channel count for its whole
 * lifetime, and processes 10 millisecond frames: [numFramesPerChannel] samples
 * per channel at [sampleRateHz]. Supported rates are 16, 32 and 48 kHz.
 *
 * Samples are carried in the int16 range `[-32768, 32767]`, the range WebRTC's
 * audio processing works in - not `[-1, 1]`. Multiply normalized audio by
 * 32768 on the way in and divide on the way out.
 *
 * The per-frame usage is:
 *
 * ```kotlin
 * suppressor.analyze(capture)   // before AEC/AGC if those are used
 * // ... AEC/AGC may modify capture here ...
 * suppressor.process(capture)   // in-place noise suppression
 * ```
 *
 * [analyze] must be called before [process] for the same frame. Instances are
 * not thread-safe.
 */
interface NoiseSuppressor : AutoCloseable {
    /** Sample rate the suppressor was created for. */
    val sampleRateHz: Int

    /** Number of interleaved channels per frame. */
    val numChannels: Int

    /** Samples per channel in one 10 ms frame: [sampleRateHz] / 100. */
    val numFramesPerChannel: Int

    /**
     * Analyses one frame without modifying it.
     *
     * [interleaved] holds `numFramesPerChannel * numChannels` samples in
     * frame-major order: `[frame0_ch0, frame0_ch1, frame1_ch0, ...]`.
     */
    fun analyze(interleaved: FloatArray)

    /**
     * Applies noise suppression to one frame in place. [interleaved] has the
     * same layout as in [analyze].
     */
    fun process(interleaved: FloatArray)

    /**
     * Allows the suppressor to skip output processing when the output is not
     * going to be used, for instance while muted. While it is `false`,
     * [process] leaves the buffer untouched.
     */
    fun setCaptureOutputUsage(captureOutputUsed: Boolean)
}

/** Creates a noise suppressor for [sampleRateHz] and [numChannels]. */
expect fun createNoiseSuppressor(
    sampleRateHz: Int,
    numChannels: Int,
    level: NsLevel = NsLevel.DB12,
): NoiseSuppressor

/** Version of the bundled WebRTC noise suppression library. */
expect fun noiseSuppressorVersion(): String

/** Sample rates WebRTC's noise suppressor supports. */
internal val SUPPORTED_SAMPLE_RATES = intArrayOf(16000, 32000, 48000)

/** Channel count the C API accepts. */
internal const val MAX_CHANNELS = 32

/** Validates the construction arguments before they reach the native library. */
internal fun requireSuppressorArguments(sampleRateHz: Int, numChannels: Int) {
    require(sampleRateHz in SUPPORTED_SAMPLE_RATES) {
        "Unsupported sample rate $sampleRateHz Hz: the noise suppressor supports 16000, 32000 and 48000"
    }
    require(numChannels in 1..MAX_CHANNELS) {
        "Unsupported channel count $numChannels: expected 1..$MAX_CHANNELS"
    }
}

/** Validates that [interleaved] holds exactly one 10 ms frame. */
internal fun requireFrameSize(interleaved: FloatArray, channels: Int, frames: Int) {
    val expected = channels * frames
    require(interleaved.size == expected) {
        "Expected one 10 ms frame of $frames samples x $channels channels = $expected samples, got ${interleaved.size}"
    }
}
