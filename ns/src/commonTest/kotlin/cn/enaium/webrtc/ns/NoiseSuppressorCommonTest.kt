package cn.enaium.webrtc.ns

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoiseSuppressorCommonTest {

    @Test
    fun versionIsReported() {
        assertTrue(noiseSuppressorVersion().isNotBlank())
    }

    @Test
    fun reportsItsConfiguration() {
        createNoiseSuppressor(16000, 1).use { ns ->
            assertEquals(16000, ns.sampleRateHz)
            assertEquals(1, ns.numChannels)
            assertEquals(160, ns.numFramesPerChannel)
        }
        createNoiseSuppressor(48000, 2, NsLevel.DB21).use { ns ->
            assertEquals(48000, ns.sampleRateHz)
            assertEquals(2, ns.numChannels)
            assertEquals(480, ns.numFramesPerChannel)
        }
    }

    @Test
    fun silenceStaysSilent() {
        createNoiseSuppressor(16000, 1).use { ns ->
            val frame = FloatArray(ns.numFramesPerChannel)
            repeat(10) {
                ns.analyze(frame)
                ns.process(frame)
            }
            assertTrue(frame.all { it == 0f })
        }
    }

    @Test
    fun attenuatesStationaryNoise() {
        createNoiseSuppressor(16000, 1, NsLevel.DB12).use { ns ->
            val frames = 250
            val frame = FloatArray(ns.numFramesPerChannel)
            var inputPower = 0.0
            var outputPower = 0.0
            var random = 0x5EED

            for (f in 0 until frames) {
                for (i in frame.indices) {
                    // Deterministic uniform noise in the int16 range; the
                    // suppressor works on that scale, not on -1..1.
                    random = random * 1103515245 + 12345
                    frame[i] = ((random ushr 16) and 0x7FFF).toFloat() - 16384f
                }
                if (f >= frames - 100) {
                    for (value in frame) inputPower += value.toDouble() * value
                }
                ns.analyze(frame)
                ns.process(frame)
                if (f >= frames - 100) {
                    for (value in frame) outputPower += value.toDouble() * value
                }
            }

            // Noise estimation needs a moment to settle; over the last second
            // the output has to be clearly below the input.
            assertTrue(
                outputPower < inputPower * 0.7,
                "Output power $outputPower should be well below input power $inputPower",
            )
        }
    }

    @Test
    fun bypassedOutputLeavesTheFrameUntouched() {
        createNoiseSuppressor(16000, 1).use { ns ->
            val frame = FloatArray(ns.numFramesPerChannel) { it.toFloat() }
            val before = frame.copyOf()
            ns.setCaptureOutputUsage(false)
            ns.analyze(frame)
            ns.process(frame)
            assertTrue(frame.contentEquals(before))
        }
    }

    @Test
    fun rejectsAWrongFrameSize() {
        createNoiseSuppressor(16000, 1).use { ns ->
            assertFailsWith<IllegalArgumentException> { ns.analyze(FloatArray(159)) }
            assertFailsWith<IllegalArgumentException> { ns.process(FloatArray(161)) }
        }
    }

    @Test
    fun rejectsUnsupportedArguments() {
        assertFailsWith<IllegalArgumentException> { createNoiseSuppressor(44100, 1) }
        assertFailsWith<IllegalArgumentException> { createNoiseSuppressor(16000, 0) }
        assertFailsWith<IllegalArgumentException> { createNoiseSuppressor(16000, MAX_CHANNELS + 1) }
    }

    @Test
    fun handlesMultipleChannels() {
        createNoiseSuppressor(32000, 2).use { ns ->
            val frame = FloatArray(ns.numFramesPerChannel * 2)
            var random = 1
            for (i in frame.indices) {
                random = random * 1103515245 + 12345
                frame[i] = ((random ushr 16) and 0x7FFF).toFloat() - 16384f
            }
            repeat(20) {
                ns.analyze(frame)
                ns.process(frame)
            }
            assertTrue(frame.all { it.isFinite() })
            var power = 0.0
            for (value in frame) power += value.toDouble() * value
            val rms = sqrt(power / frame.size)
            assertTrue(rms > 0.0)
        }
    }
}
