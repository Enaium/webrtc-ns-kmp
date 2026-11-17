@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.webrtc.ns

import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * Native counterpart of the JVM fixture test: it proves that the cinterop klib
 * embeds the static library and that the whole pipeline gives the same result
 * on Kotlin/Native. Runs on macOS, where the CI runner can link and execute it.
 */
class NoiseSuppressorNativeTest {

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100
        const val MAX_LAG = 1000
        const val SEARCH_SAMPLES = 16000
        const val NOISY_PATH = "webrtc-ns/tests/noisy.wav"
        const val CLEAN_PATH = "webrtc-ns/tests/clean.wav"
    }

    private fun readFileBytes(path: String): ByteArray = memScoped {
        val file = fopen(path, "rb") ?: error("Cannot open file: $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            fseek(file, 0, SEEK_SET)
            val buffer = allocArray<ByteVar>(size)
            fread(buffer, 1u, size.toULong(), file)
            ByteArray(size.toInt()) { buffer[it] }
        } finally {
            fclose(file)
        }
    }

    private fun readWavSamples(path: String): ShortArray {
        val bytes = readFileBytes(path)
        var offset = 12
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = bytes.decodeToString(offset, offset + 4, false)
            val size = readLe32(bytes, offset + 4)
            if (id == "data") {
                dataOffset = offset + 8
                dataSize = size
                break
            }
            offset += 8 + size
        }
        require(dataOffset >= 0 && dataOffset + dataSize <= bytes.size) { "No data chunk in $path" }
        return ShortArray(dataSize / 2) { readLe16(bytes, dataOffset + it * 2) }
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLe16(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()

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
    fun denoisesTheFixtureRecording() {
        val noisy = readWavSamples(NOISY_PATH)
        val clean = readWavSamples(CLEAN_PATH)
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
            "native NS test: frames=${total / FRAME_SAMPLES} lag=$lag " +
                "inputSnr=$inputSnr dB outputSnr=$outputSnr dB improvement=$improvement dB " +
                "inputRms=${rmsDb(noisyFloats)} dB outputRms=${rmsDb(output)} dB",
        )

        assertTrue(
            improvement > 1.5,
            "SNR should improve by more than 1.5 dB, was $improvement",
        )
        assertTrue(
            rmsDb(output) < rmsDb(noisyFloats),
            "Output should be quieter than the noisy input",
        )
    }
}
