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
 * Live noise suppression visualization, shared by the JVM and native entry
 * points: the 48 kHz mono microphone capture is suppressed by [NsPipeline], the
 * result is played back, and both signals are drawn with ImPlot in an SDL
 * window.
 *
 * The scopes are sized for the longest window the UI can show, so the slider
 * can widen it without touching the pipeline.
 *
 * [frames] bounds the run, which is what the automated (headless) runs use.
 * Returns `false` when no window could be opened.
 */
fun runWaveformExample(frames: Int = Int.MAX_VALUE): Boolean {
    println("webrtc-ns-kmp waveform example (frames=$frames)")
    val displayed = NsPipeline(windowSamples(MAX_WINDOW_MILLIS)).use { pipeline ->
        val window = WaveformWindow(pipeline)
        // Capture, suppression and playback run on the pipeline's own thread;
        // this loop only draws (and reacts to) what it publishes.
        pipeline.start()
        val rendered = ImGuiSdlApp.run("webrtc-ns-kmp waveform", frames) {
            window.draw()
        }
        // What a headless run has to show for itself: how much audio was
        // processed and what the suppressor did to its level.
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
        rendered
    }
    println(if (displayed) "done" else "aborted")
    return displayed
}
