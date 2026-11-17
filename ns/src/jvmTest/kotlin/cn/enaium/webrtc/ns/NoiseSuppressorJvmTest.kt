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
 * End-to-end checks against the WAV fixtures of the webrtc-ns submodule: the
 * output is compared with the clean reference, and the noise-only stretches
 * show whether the stationary noise was actually removed.
 *
 * The suppressor delays its output by the overlap-add synthesis (6 ms) plus the
 * analysis filter bank at 32/48 kHz, so the output is aligned by
 * cross-correlation before the SNR is computed - the same measurement
 * `tests/ns_test.cc` in webrtc-ns does.
 *
 * The 48 kHz fixture is the 16 kHz one resampled with ffmpeg. It is here because
 * the 32/48 kHz path runs through a 2/3-band analysis filter bank whose state is
 * easy to advance twice per frame (once in analyze and once in process), which
 * leaves broadband static in the output. 16 kHz has a single band and cannot
 * catch that.
 */
class NoiseSuppressorJvmTest {

    private companion object {
        const val CHANNELS = 1
        const val MAX_LAG = 1000
        const val NOISY_PATH = "webrtc-ns/tests/noisy.wav"
        const val CLEAN_PATH = "webrtc-ns/tests/clean.wav"
        const val NOISY_48K_RESOURCE = "/noisy-48k.wav"
        const val CLEAN_48K_RESOURCE = "/clean-48k.wav"
    }

    private data class Wav(val samples: ShortArray)

    private fun parseWav(bytes: ByteArray, name: String): Wav {
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
        require(dataOffset >= 0 && dataOffset + dataSize <= bytes.size) { "No data chunk in $name" }
        val samples = ShortArray(dataSize / 2)
        ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return Wav(samples)
    }

    /** Minimal PCM16 mono WAV reader, independent of javax.sound. */
    private fun readWavFile(path: String): Wav = parseWav(Files.readAllBytes(Path.of(path)), path)

    private fun readWavResource(name: String): Wav {
        val bytes = requireNotNull(javaClass.getResourceAsStream(name)) {
            "Test resource $name is missing"
        }.use { it.readBytes() }
        return parseWav(bytes, name)
    }

    private fun denoise(input: ShortArray, sampleRate: Int, level: NsLevel): FloatArray {
        val frameSamples = sampleRate / 100
        val frames = input.size / frameSamples
        val output = FloatArray(frames * frameSamples)
        createNoiseSuppressor(sampleRate, CHANNELS, level).use { ns ->
            val frame = FloatArray(frameSamples)
            for (f in 0 until frames) {
                val offset = f * frameSamples
                for (i in 0 until frameSamples) frame[i] = input[offset + i].toFloat()
                ns.analyze(frame)
                ns.process(frame)
                for (i in 0 until frameSamples) output[offset + i] = frame[i]
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

    private fun rmsDb(signal: FloatArray): Double {
        var power = 0.0
        for (value in signal) power += value.toDouble() * value
        val rms = sqrt(power / signal.size).coerceAtLeast(1e-10)
        return 20.0 * log10(rms / 32768.0)
    }

    private fun evaluate(
        name: String,
        noisy: Wav,
        clean: Wav,
        sampleRate: Int,
        level: NsLevel,
    ): Double {
        val frameSamples = sampleRate / 100
        val total = minOf(noisy.samples.size, clean.samples.size) / frameSamples * frameSamples

        val noisyFloats = FloatArray(total) { noisy.samples[it].toFloat() }
        val cleanFloats = FloatArray(total) { clean.samples[it].toFloat() }
        val output = denoise(noisy.samples.copyOf(total), sampleRate, level)

        val search = minOf(sampleRate, total - MAX_LAG - 1)
        val lag = bestLag(output, cleanFloats, search)
        val count = total - lag
        val inputSnr = snrDb(noisyFloats, cleanFloats, 0, count)
        val outputSnr = snrDb(output, cleanFloats, lag, count)
        val improvement = outputSnr - inputSnr

        println(
            "$name: frames=${total / frameSamples} lag=$lag " +
                "inputSnr=${"%.2f".format(inputSnr)}dB outputSnr=${"%.2f".format(outputSnr)}dB " +
                "improvement=${"%.2f".format(improvement)}dB " +
                "inputRms=${"%.2f".format(rmsDb(noisyFloats))}dB outputRms=${"%.2f".format(rmsDb(output))}dB",
        )
        assertTrue(rmsDb(output) < rmsDb(noisyFloats)) { "Output should be quieter than the noisy input" }
        return improvement
    }

    @Test
    fun denoisesThe16kFixtureRecording() {
        val improvement = evaluate(
            "JVM NS test 16 kHz",
            readWavFile(NOISY_PATH),
            readWavFile(CLEAN_PATH),
            16000,
            NsLevel.DB12,
        )
        assertTrue(improvement > 1.5) { "SNR should improve by more than 1.5 dB, was $improvement" }
    }

    @Test
    fun denoisesThe48kFixtureRecording() {
        val improvement = evaluate(
            "JVM NS test 48 kHz",
            readWavResource(NOISY_48K_RESOURCE),
            readWavResource(CLEAN_48K_RESOURCE),
            48000,
            NsLevel.DB6,
        )
        // Before the per-frame filter-bank fix this was negative (-2.3 dB):
        // the suppressor removed noise but left more broadband error than it
        // removed, which is audible as static. Positive improvement is what
        // separates the fixed path from that one; the 3-band filter bank itself
        // limits 48 kHz to less than the 16 kHz figure.
        assertTrue(improvement > 0.2) { "SNR should improve at 48 kHz too, was $improvement" }
    }
}
