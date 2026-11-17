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
 * Platform specific advice appended when SDL cannot open a video device, or
 * empty when the platform has nothing to add.
 */
internal expect val videoInitHint: String

/**
 * Whether SDL should make the window fullscreen as soon as it is created.
 *
 * On Android the SDL window always covers the whole display, and fullscreen is
 * what makes SDL hide the status and navigation bars, which would otherwise
 * draw on top of the ImGui window (SDLActivity owns that decision, so hiding
 * them from the activity is not enough).
 */
internal expect val windowStartsFullscreen: Boolean

/**
 * UI scale the host reports: the display density on Android, 1.0 everywhere
 * else. The font, the style sizes and the window's fixed sizes are laid out
 * for it, so a 2712 px phone gets touch sized controls instead of 13 px text.
 *
 * Android runs this example as a native library, so it cannot read
 * `DisplayMetrics` itself; the activity reports the density over JNI (see
 * `MainActivity.nativeSetUiScale`) before SDL starts.
 */
internal expect var uiScale: Float
