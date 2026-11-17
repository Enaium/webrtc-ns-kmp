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

package cn.enaium.webrtc.ns.examples.waveform

import cn.enaium.audio.AudioBuffer
import cn.enaium.audio.AudioException
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioInput
import cn.enaium.audio.AudioOutput
import cn.enaium.audio.AudioSystem
import cn.enaium.audio.audioSystem
import cn.enaium.sdl.SDL
import cn.enaium.webrtc.ns.NoiseSuppressor
import cn.enaium.webrtc.ns.NsLevel
import cn.enaium.webrtc.ns.createNoiseSuppressor
import kotlin.concurrent.Volatile
import kotlin.math.log10
import kotlin.math.sqrt

/** The example runs the suppressor at 48 kHz mono. */
internal const val SAMPLE_RATE = 48_000

/** Samples per frame: 10 ms at 48 kHz, the size NS is built for. */
internal const val FRAME_SAMPLES = SAMPLE_RATE / 100

/**
 * Frames the capture device buffers, in 10 ms frames: 3 x 10 ms.
 *
 * Deep enough to ride out a scheduling hiccup - the audio thread is a plain one
 * and the render loop can hold the CPU for over a hundred milliseconds - because
 * an input queue that overflows drops microphone samples.
 */
private const val BUFFER_FRAMES = 3

/**
 * Frames the playback device buffers: 10 x 10 ms.
 *
 * Deeper than the capture on purpose: the loop hands over whatever the capture
 * collected in one go, and the playback device has to absorb that burst
 * without dropping it.
 */
private const val PLAYBACK_BUFFER_FRAMES = 10

/** Milliseconds between two attempts to open a device. */
private const val RETRY_DELAY_MS = 250

/**
 * Frames of silence written into the playback queue before the loop takes
 * over: the queue has [PLAYBACK_BUFFER_FRAMES] of room, and leaving a couple of
 * frames unused keeps the priming writes from blocking.
 */
private const val PLAYBACK_PRIME_FRAMES = PLAYBACK_BUFFER_FRAMES - 2

/** 10 ms frames between two status lines: one second of audio. */
private const val REPORT_FRAMES = 100

/**
 * The suppressor works on samples in the int16 range, not on `-1..1`:
 * `audio-io-kmp` and the plots use `-1..1`, so the frames are scaled on the way
 * in and out. Feeding `-1..1` floats instead makes the noise estimator treat
 * the capture as near-silence and nothing is attenuated.
 */
private const val NS_SCALE = 32_768f

/**
 * The audio half of the example, on a thread of its own ([start]):
 *
 *  - The default input device is captured at NS's fixed 48 kHz mono format,
 *    every 10 ms frame is fed to [NoiseSuppressor.analyze] +
 *    [NoiseSuppressor.process], and the result is played back through the
 *    default output device, so the suppression is audible and not just visible.
 *  - The raw capture and the suppressed output are published into
 *    [inputScope] / [outputScope] for the plots.
 *
 * The loop blocks in `AudioInput.read`, so the devices - not the render loop -
 * decide the pace. That keeps the window responsive when a device stalls.
 *
 * The render loop reads scopes and the control properties, and writes the
 * controls; everything crossing that boundary is either volatile or published
 * through the atomics inside [WaveformScope].
 */
class NsPipeline(
    /**
     * Samples covered by one waveform window; the example sizes it for the
     * longest window its slider allows and lets the UI show a shorter part.
     */
    windowSamples: Int,
) : AutoCloseable {

    /** Capture (microphone) samples, as recorded. */
    val inputScope = WaveformScope(windowSamples)

    /** Noise-suppressed samples, aligned with [inputScope] apart from NS's delay. */
    val outputScope = WaveformScope(windowSamples)

    /** Format requested from the devices. */
    val format: AudioFormat = AudioFormat.SPEECH

    /** Samples per NS frame (480 = 10 ms at 48 kHz). */
    val frameSize: Int = FRAME_SAMPLES

    /** Backend serving the devices, e.g. `"Core Audio"`. */
    val systemName: String

    /** Whether the capture goes through the suppressor; off plays the raw microphone back. */
    @Volatile
    var nsEnabled: Boolean = true

    /** Whether the processed capture is played back. */
    @Volatile
    var playbackEnabled: Boolean = true

    /** Suppression target; changing it rebuilds the suppressor on the audio thread. */
    @Volatile
    var level: NsLevel = NsLevel.DB12

    /** Human readable name of the capture endpoint, once one is open. */
    @Volatile
    var captureDeviceName: String = "system default"
        private set

    /** Human readable name of the playback endpoint, once one is open. */
    @Volatile
    var playbackDeviceName: String = "system default"
        private set

    /** Why no capture stream is open, or `null` while one is. */
    @Volatile
    var captureError: String? = null
        private set

    /** Why no playback stream is open, or `null` while one is. */
    @Volatile
    var playbackError: String? = null
        private set

    /** Frames processed since the pipeline was created. */
    @Volatile
    var processedFrames: Long = 0L
        private set

    /** Playback frames the output device could not take in time. */
    @Volatile
    var droppedPlaybackFrames: Long = 0L
        private set

    /**
     * Runs of silence longer than [GAP_SAMPLES] in the played signal: what an
     * underrun or a dropped frame sounds like.
     */
    @Volatile
    var playbackGaps: Long = 0L
        private set

    /** Runs of silence in the captured signal. */
    @Volatile
    var captureGaps: Long = 0L
        private set

    /** Samples the suppressor pushed past full scale and the playback had to clamp. */
    @Volatile
    var clippedSamples: Long = 0L
        private set

    /** Input level of the last second, in dBFS. */
    @Volatile
    var inputDb: Double = -200.0
        private set

    /** Output level of the last second, in dBFS. */
    @Volatile
    var outputDb: Double = -200.0
        private set

    private val system: AudioSystem = audioSystem()

    @Volatile
    private var input: AudioInput? = null

    @Volatile
    private var output: AudioOutput? = null

    /** The suppressor belongs to the audio thread once [start] was called. */
    private var suppressor: NoiseSuppressor? = null

    private var suppressorLevel: NsLevel? = null

    // Scratch for one device read. It must fit whatever the device hands back
    // in a single call, otherwise the conversion would silently clip the rest.
    private val readBuffer: AudioBuffer

    private val readFloats: FloatArray

    /** Playback for one device read: [burstFloats] filled by the frames, written once. */
    private val burst: AudioBuffer

    private val burstFloats: FloatArray

    private var burstFrames = 0

    private val microphone = FloatArray(FRAME_SAMPLES)

    /** Suppressed frame in `-1..1`, aligned with [microphone]. */
    private val capture = FloatArray(FRAME_SAMPLES)

    /** Frame in the int16 range NS expects. */
    private val scaled = FloatArray(FRAME_SAMPLES)

    private val playback = FloatArray(FRAME_SAMPLES)

    // Carry-over for the partial frame between two reads; always shorter than
    // one frame.
    private val carry = FloatArray(FRAME_SAMPLES)

    private var carryLength = 0

    private var reportInputPower = 0.0

    private var reportOutputPower = 0.0

    private var reportFrames = 0

    @Volatile
    private var closed = false

    private var worker: AudioThread? = null

    init {
        systemName = system.name
        readBuffer = AudioBuffer(format, FRAME_SAMPLES * BUFFER_FRAMES)
        readFloats = FloatArray(FRAME_SAMPLES * BUFFER_FRAMES)
        burst = AudioBuffer(format, FRAME_SAMPLES * BUFFER_FRAMES)
        burstFloats = FloatArray(FRAME_SAMPLES * BUFFER_FRAMES)
    }

    /** Starts the audio thread. */
    fun start() {
        if (worker != null || closed) return
        worker = AudioThread("webrtc-ns-waveform-audio") { audioLoop() }.also { it.start() }
    }

    /**
     * Runs until [close], paced by the capture device: the blocking read returns
     * as soon as the device has audio, and the frames it returns are processed
     * and played back in the same order.
     */
    private fun audioLoop() {
        while (!closed) {
            val capture = input ?: openCapture()
            if (capture == null) {
                SDL.delay(RETRY_DELAY_MS)
                continue
            }
            input = capture

            // Playback is optional: without an output device the example still
            // captures and suppresses, it just cannot be heard.
            val playbackStream = output ?: openPlayback()?.also { output = it }

            val frames = capture.read(readBuffer)
            // A stopped or closed stream unblocks the wait with -1; that is how
            // close() gets this thread out of the read.
            if (frames < 0) return
            if (frames == 0) continue

            process(frames, playbackStream)
        }
    }

    private fun openCapture(): AudioInput? = try {
        val stream = system.openInput(format, bufferFrames = FRAME_SAMPLES * BUFFER_FRAMES)
        check(stream.format == format) {
            "NS needs 48 kHz mono, but the device opened as ${stream.format}"
        }
        stream.start()
        captureDeviceName = stream.device?.name ?: system.defaultInputDevice()?.name ?: "system default"
        captureError = null
        report("capturing $captureDeviceName [$systemName] $format")
        stream
    } catch (e: AudioException) {
        if (captureError == null) {
            val available = system.inputDevices().joinToString { it.name }
            captureError = "${e.message} - inputs: [$available]"
            report("capture unavailable: $captureError; retrying")
        }
        null
    }

    private fun openPlayback(): AudioOutput? = try {
        val stream = system.openOutput(format, bufferFrames = FRAME_SAMPLES * PLAYBACK_BUFFER_FRAMES)
        check(stream.format == format) {
            "NS needs 48 kHz mono, but the device opened as ${stream.format}"
        }
        stream.start()

        // Prime the queue. The loop feeds the device just in time - one frame
        // per captured frame - which leaves the queue almost empty, and a
        // device that runs dry plays silence for the missing frames: that is the
        // stutter this hears as broken playback, and no counter on this side can
        // see it because the gap is inserted after the write. Filling the queue
        // up front gives jitter something to eat into; the equal in and out
        // rates keep the level where it started.
        val silence = AudioBuffer(format, FRAME_SAMPLES)
        silence.putFloats(FloatArray(FRAME_SAMPLES), FRAME_SAMPLES)
        repeat(PLAYBACK_PRIME_FRAMES) { stream.write(silence) }

        playbackDeviceName = stream.device?.name ?: system.defaultOutputDevice()?.name ?: "system default"
        playbackError = null
        report("playing to $playbackDeviceName [$systemName] $format")
        stream
    } catch (e: AudioException) {
        if (playbackError == null) {
            val available = system.outputDevices().joinToString { it.name }
            playbackError = "${e.message} - outputs: [$available]"
            report("playback unavailable: $playbackError; retrying")
        }
        null
    }

    /** Suppresses every complete frame in the first [frames] captured frames. */
    private fun process(frames: Int, playbackStream: AudioOutput?): Int {
        val count = readBuffer.toFloats(readFloats, frames)
        var offset = 0
        var processed = 0

        // Complete the partial frame left over from the previous read first.
        if (carryLength > 0) {
            val needed = FRAME_SAMPLES - carryLength
            if (count < needed) {
                readFloats.copyInto(carry, carryLength, 0, count)
                carryLength += count
                return 0
            }
            readFloats.copyInto(carry, carryLength, 0, needed)
            processFrame(carry, playbackStream)
            processed += FRAME_SAMPLES
            offset = needed
            carryLength = 0
        }

        while (offset + FRAME_SAMPLES <= count) {
            readFloats.copyInto(microphone, 0, offset, offset + FRAME_SAMPLES)
            processFrame(microphone, playbackStream)
            processed += FRAME_SAMPLES
            offset += FRAME_SAMPLES
        }

        val remaining = count - offset
        if (remaining > 0) {
            readFloats.copyInto(carry, 0, offset, count)
            carryLength = remaining
        }
        flushPlayback(playbackStream)
        return processed
    }

    /**
     * Hands every frame collected since the last read to the device in a single
     * write: one per read instead of one per 10 ms frame.
     */
    private fun flushPlayback(stream: AudioOutput?) {
        val frames = burstFrames
        burstFrames = 0
        if (stream == null || frames == 0) return
        burst.putFloats(burstFloats, frames * FRAME_SAMPLES)
        // Blocking: dropping the tail leaves a step in the signal, and a device
        // that stops consuming parks this thread instead of the window - close()
        // unblocks it by closing the stream.
        val written = stream.write(burst)
        if (written < frames) droppedPlaybackFrames += (frames - written).toLong()
    }

    /**
     * One 10 ms frame end to end: the microphone frame goes through the
     * suppressor, both signals are published and the result is played back.
     */
    private fun processFrame(microphone: FloatArray, playbackStream: AudioOutput?) {
        inputScope.append(microphone, FRAME_SAMPLES)

        if (nsEnabled) {
            if (suppressor == null || suppressorLevel != level) {
                suppressor?.close()
                suppressor = createNoiseSuppressor(SAMPLE_RATE, 1, level)
                suppressorLevel = level
            }
            for (i in 0 until FRAME_SAMPLES) scaled[i] = microphone[i] * NS_SCALE
            val ns = suppressor ?: error("suppressor was not created")
            ns.analyze(scaled)
            ns.process(scaled)
            for (i in 0 until FRAME_SAMPLES) {
                val value = scaled[i] / NS_SCALE
                if (value > 1f || value < -1f) clippedSamples++
                capture[i] = value.coerceIn(-1f, 1f)
            }
        } else {
            microphone.copyInto(capture, 0, 0, FRAME_SAMPLES)
        }

        outputScope.append(capture, FRAME_SAMPLES)

        if (playbackStream != null) {
            val audible = playbackEnabled
            for (i in 0 until FRAME_SAMPLES) {
                playback[i] = if (audible) capture[i] else 0f
            }
            playback.copyInto(burstFloats, burstFrames * FRAME_SAMPLES, 0, FRAME_SAMPLES)
            burstFrames++
            if (audible) playbackGaps += countGaps(playback)
        }
        captureGaps += countGaps(microphone)

        processedFrames++
        accumulateLevels(microphone, capture)
    }

    private fun accumulateLevels(input: FloatArray, output: FloatArray) {
        for (i in 0 until FRAME_SAMPLES) {
            reportInputPower += input[i].toDouble() * input[i]
            reportOutputPower += output[i].toDouble() * output[i]
        }
        reportFrames += FRAME_SAMPLES
        if (processedFrames % REPORT_FRAMES == 0L) {
            inputDb = toDbfs(reportInputPower, reportFrames)
            outputDb = toDbfs(reportOutputPower, reportFrames)
            report("$processedFrames frames: input ${fixed(inputDb, 1)} dBFS, " +
                "output ${fixed(outputDb, 1)} dBFS, ${fixed(outputDb - inputDb, 1)} dB change, " +
                "drops $droppedPlaybackFrames, gaps $playbackGaps, input gaps $captureGaps, " +
                "clip $clippedSamples")
            reportInputPower = 0.0
            reportOutputPower = 0.0
            reportFrames = 0
        }
    }

    /** Stops the audio thread and releases the devices and the suppressor. */
    override fun close() {
        if (closed) return
        closed = true
        // Closing the streams unblocks a thread parked in read(); join() then
        // guarantees it is out before anything it uses is destroyed.
        input?.close()
        output?.close()
        worker?.join()
        worker = null
        input = null
        output = null
        suppressor?.close()
        suppressor = null
        system.close()
    }

    private companion object {
        /**
         * Silence longer than this reads as a gap rather than as a quiet moment.
         * 50 ms: anything shorter is just a quiet signal, which quantises to a
         * few consecutive zero samples and would otherwise count as a stall.
         */
        const val GAP_SAMPLES = SAMPLE_RATE / 20

        /**
         * Counts the runs of silence in a frame: an output device that misses a
         * buffer plays exactly zeros, so a run of them is the signature of the
         * glitches that are audible as crackle.
         */
        fun countGaps(values: FloatArray): Long {
            var gaps = 0L
            var run = 0
            for (value in values) {
                if (value == 0f) {
                    run++
                } else {
                    if (run >= GAP_SAMPLES) gaps++
                    run = 0
                }
            }
            if (run >= GAP_SAMPLES) gaps++
            return gaps
        }

        fun toDbfs(power: Double, samples: Int): Double {
            if (samples == 0 || power <= 0.0) return -200.0
            return 20.0 * log10(sqrt(power / samples))
        }
    }
}
