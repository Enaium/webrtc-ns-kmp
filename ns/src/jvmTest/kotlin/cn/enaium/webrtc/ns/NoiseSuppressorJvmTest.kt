package cn.enaium.webrtc.ns

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end check against the WAV fixtures of the webrtc-ns submodule: the
 * output is compared with the clean reference, and the noise-only stretches
 * show whether the stationary noise was actually removed.
 *
 * The suppressor delays its output by the overlap-add synthesis (6 ms at
 * 16 kHz), so the output is aligned by cross-correlation before the SNR is
 * computed - the same measurement `tests/ns_test.cc` in webrtc-ns does.
 */
class NoiseSuppressorJvmTest {

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = SAMPLE_RATE / 100
        const val MAX_LAG = 1000
        const val SEARCH_SAMPLES = 16000
        const val NOISY_PATH = "webrtc-ns/tests/noisy.wav"
        const val CLEAN_PATH = "webrtc-ns/tests/clean.wav"
    }

    private data class Wav(val samples: ShortArray)

    /** Minimal PCM16 mono WAV reader, independent of javax.sound. */
    private fun readWav(path: String): Wav {
        val bytes = Files.readAllBytes(Path.of(path))
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
        require(dataOffset >= 0 && dataOffset + dataSize <= bytes.size) { "No data chunk in $path" }
        val samples = ShortArray(dataSize / 2)
        ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return Wav(samples)
    }

    private fun denoise(input: ShortArray): FloatArray {
        val frames = input.size / FRAME_SAMPLES
        val output = FloatArray(frames * FRAME_SAMPLES)
        createNoiseSuppressor(SAMPLE_RATE, CHANNELS, NsLevel.DB12).use { ns ->
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

    /** Lag in samples that best aligns [signal] with the first [count] samples of [reference]. */
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

    /** SNR of [signal] (shifted by [lag]) against [reference], in dB. */
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

    private fun rmsDb(signal: FloatArray, count: Int = signal.size): Double {
        var power = 0.0
        for (i in 0 until count) power += signal[i].toDouble() * signal[i]
        val rms = sqrt(power / count).coerceAtLeast(1e-10)
        return 20.0 * log10(rms / 32768.0)
    }

    @Test
    fun denoisesTheFixtureRecording() {
        val noisy = readWav(NOISY_PATH).samples
        val clean = readWav(CLEAN_PATH).samples
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
            "JVM NS test: frames=${total / FRAME_SAMPLES} lag=$lag " +
                "inputSnr=${"%.2f".format(inputSnr)}dB outputSnr=${"%.2f".format(outputSnr)}dB " +
                "improvement=${"%.2f".format(improvement)}dB " +
                "inputRms=${"%.2f".format(rmsDb(noisyFloats))}dB outputRms=${"%.2f".format(rmsDb(output))}dB",
        )

        assertTrue(improvement > 1.5) { "SNR should improve by more than 1.5 dB, was $improvement" }
        assertTrue(rmsDb(output) < rmsDb(noisyFloats)) { "Output should be quieter than the noisy input" }
    }
}
