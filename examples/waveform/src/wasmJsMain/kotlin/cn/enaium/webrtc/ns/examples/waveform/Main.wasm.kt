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

import kotlinx.browser.window

/**
 * Browser entry point: the page initializes SDL, Dear ImGui and the WebRTC NS
 * Emscripten modules before this module runs, then animation frames drive both
 * the UI and the polled Web Audio backend.
 *
 * Audio starts paused: an `AudioContext` may only start after a user gesture,
 * so the first click on the UI's "audio" checkbox opens the microphone.
 */
fun main() {
    println("webrtc-ns-kmp waveform example (wasm)")
    val app = WaveformApp.start(startPaused = true, threaded = false) ?: run {
        println("aborted")
        return
    }
    fun tick() {
        if (app.step()) {
            window.requestAnimationFrame { tick() }
        } else {
            app.close()
            println("done")
        }
    }
    window.requestAnimationFrame { tick() }
}
