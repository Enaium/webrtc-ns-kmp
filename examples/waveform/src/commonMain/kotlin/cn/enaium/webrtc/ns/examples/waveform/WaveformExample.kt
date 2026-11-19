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

/**
 * Live noise suppression visualization, shared by the JVM, native and browser
 * entry points: the 48 kHz mono microphone capture is suppressed by
 * [NsPipeline], the result is played back, and both signals are drawn with
 * ImPlot in an SDL window.
 *
 * [WaveformApp.run] is the blocking loop of the desktop and native entry
 * points. The browser cannot block its page, so its entry point calls
 * [WaveformApp.start] and drives [WaveformSession.step] from
 * `requestAnimationFrame`; that mode also polls the audio backend instead of
 * starting the audio thread.
 */
object WaveformApp {

    /** A running example: [step] renders (and polls audio), [close] releases it. */
    class WaveformSession internal constructor(
        private val pipeline: NsPipeline,
        private val ui: ImGuiSdlApp.Session,
        private val pumpAudio: Boolean,
    ) : AutoCloseable {

        private var closed = false

        /** Advances one frame; `false` once the window was closed. */
        fun step(): Boolean {
            if (closed) return false
            // A threaded pipeline runs on its own thread; the browser has none
            // and is driven from here instead.
            if (pumpAudio) pipeline.pump()
            return ui.step()
        }

        /** Releases the window and the audio pipeline. Idempotent. */
        override fun close() {
            if (closed) return
            closed = true
            try {
                ui.close()
            } finally {
                println(
                    "processed ${pipeline.processedFrames} frames of ${pipeline.frameSize} samples at " +
                        "${pipeline.level} suppression: input ${fixed(pipeline.inputDb, 1)} dBFS, " +
                        "output ${fixed(pipeline.outputDb, 1)} dBFS" +
                        if (pipeline.droppedPlaybackFrames > 0L) {
                            ", ${pipeline.droppedPlaybackFrames} playback frames dropped"
                        } else {
                            ""
                        },
                )
                pipeline.close()
            }
        }
    }

    /**
     * Opens the window and the pipeline.
     *
     * [startPaused] keeps the audio devices closed until the UI is unpaused;
     * browsers need that, because an `AudioContext` may only start after a user
     * gesture. [threaded] starts the pipeline's blocking audio thread; the
     * browser passes `false` and lets [WaveformSession.step] poll instead.
     */
    fun start(startPaused: Boolean = false, threaded: Boolean = true): WaveformSession? {
        val pipeline = NsPipeline(windowSamples(MAX_WINDOW_MILLIS))
        pipeline.paused = startPaused
        val window = WaveformWindow(pipeline)
        val ui = try {
            ImGuiSdlApp.start("webrtc-ns-kmp waveform") { window.draw() }
        } catch (t: Throwable) {
            pipeline.close()
            throw t
        } ?: run {
            pipeline.close()
            return null
        }
        if (threaded) pipeline.start()
        return WaveformSession(pipeline, ui, pumpAudio = !threaded)
    }

    /**
     * Runs until [frames] frames were rendered or the window closes; returns
     * `false` when no window could be opened.
     */
    fun run(frames: Int = Int.MAX_VALUE): Boolean {
        val session = start(startPaused = false, threaded = true) ?: return false
        var frame = 0
        try {
            while (frame < frames && session.step()) {
                frame++
            }
        } finally {
            session.close()
        }
        println("done")
        return true
    }
}

/**
 * Blocking entry point of the JVM and native executables. The browser uses
 * [WaveformApp.start] with `threaded = false` instead.
 *
 * [frames] bounds the run, which is what the automated (headless) runs use.
 * Returns `false` when no window could be opened.
 */
fun runWaveformExample(frames: Int = Int.MAX_VALUE): Boolean {
    println("webrtc-ns-kmp waveform example (frames=$frames)")
    val displayed = WaveformApp.run(frames)
    if (!displayed) println("aborted")
    return displayed
}
