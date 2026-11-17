package cn.enaium.webrtc.ns.examples.basic

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.enaium.webrtc.ns.NsLevel
import cn.enaium.webrtc.ns.createNoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the JNI binding inside an Android app: the AAR's libwebrtc_ns_jni.so is
 * loaded from jniLibs, the fixture recording is denoised, and the result is
 * compared with the clean reference.
 */
@RunWith(AndroidJUnit4::class)
class NsNoiseSuppressionTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = SAMPLE_RATE / 100
        private const val MAX_LAG = 1000
        private const val SEARCH_SAMPLES = 16000
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().context

    private fun readAsset(name: String): ByteArray = context().assets.open(name).use { it.readBytes() }

    private fun readWavSamples(bytes: ByteArray): ShortArray {
        var offset = 12
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataOffset = offset + 8
                dataSize = size
                break
            }
            offset += 8 + size
        }
        require(dataOffset >= 0 && dataOffset + dataSize <= bytes.size) { "No data chunk" }
        val samples = ShortArray(dataSize / 2)
        ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return samples
    }

    private fun denoise(input: ShortArray): FloatArray {
        val frames = input.size / FRAME_SAMPLES
        val output = FloatArray(frames * FRAME_SAMPLES)
        createNoiseSuppressor(SAMPLE_RATE, 1, NsLevel.DB12).use { ns ->
            val frame = FloatArray(FRAME_SAMPLES)
            for (f in 0 until frames) {
                val offset = f * FRAME_SAMPLES
                for (i in 0 until FRAME_SAMPLES) frame[i] = input[offset + i].toFloat()
                ns.analyze(frame)
                ns.process(frame)
                for (i in 0 until FRAME_SAMPLES) output[offset + i] = frame[i]
            }
        }
        return output
    }

    private fun bestLag(signal: FloatArray, reference: FloatArray, count: Int): Int {
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in 0..MAX_LAG) {
            var dot = 0.0
            var referencePower = 0.0
            for (i in 0 until count) {
                val r = reference[i].toDouble()
                dot += signal[i + lag] * r
                referencePower += r * r
            }
            if (referencePower <= 0.0) continue
            val score = dot / sqrt(referencePower)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return bestLag
    }

    private fun snrDb(signal: FloatArray, reference: FloatArray, lag: Int, count: Int): Double {
        var referencePower = 0.0
        var errorPower = 0.0
        for (i in 0 until count) {
            val r = reference[i].toDouble()
            val s = signal[i + lag].toDouble()
            referencePower += r * r
            errorPower += (s - r) * (s - r)
        }
        return 10.0 * log10(referencePower / errorPower.coerceAtLeast(1e-12))
    }

    private fun rmsDb(signal: FloatArray): Double {
        var power = 0.0
        for (value in signal) power += value.toDouble() * value
        val rms = sqrt(power / signal.size).coerceAtLeast(1e-10)
        return 20.0 * log10(rms / 32768.0)
    }

    @Test
    fun noiseSuppressionImprovesTheSnr() {
        val noisy = readWavSamples(readAsset("noisy.wav"))
        val clean = readWavSamples(readAsset("clean.wav"))
        val total = minOf(noisy.size, clean.size) / FRAME_SAMPLES * FRAME_SAMPLES

        val noisyFloats = FloatArray(total) { noisy[it].toFloat() }
        val cleanFloats = FloatArray(total) { clean[it].toFloat() }
        val output = denoise(noisy.copyOf(total))

        val lag = bestLag(output, cleanFloats, minOf(SEARCH_SAMPLES, total - MAX_LAG - 1))
        val count = total - lag
        val inputSnr = snrDb(noisyFloats, cleanFloats, 0, count)
        val outputSnr = snrDb(output, cleanFloats, lag, count)
        val improvement = outputSnr - inputSnr

        println(
            "Android NS test: frames=${total / FRAME_SAMPLES} lag=$lag " +
                "inputSnr=$inputSnr dB outputSnr=$outputSnr dB improvement=$improvement dB " +
                "inputRms=${rmsDb(noisyFloats)} dB outputRms=${rmsDb(output)} dB",
        )

        assertTrue("SNR should improve by more than 1.5 dB, was $improvement", improvement > 1.5)
        assertTrue("Output should be quieter than the noisy input", rmsDb(output) < rmsDb(noisyFloats))
    }
}
