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
 * The browser has no blocking audio thread: its audio backend is polled from
 * the page loop instead. [WaveformApp.start] therefore passes `threaded =
 * false`, and [WaveformSession.step] calls `NsPipeline.pump` on every
 * animation frame. Starting a thread here would freeze the page, so it is
 * deliberately not supported.
 */
internal actual class AudioThread actual constructor(
    @Suppress("UNUSED_PARAMETER") name: String,
    @Suppress("UNUSED_PARAMETER") body: () -> Unit,
) {

    actual fun start() {
        error("the browser has no blocking audio thread; drive NsPipeline.pump() from the page loop")
    }

    actual fun join() = Unit
}
