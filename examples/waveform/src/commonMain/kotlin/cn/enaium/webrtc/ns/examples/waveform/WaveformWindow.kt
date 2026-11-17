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

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotAxisFlags
import cn.enaium.imgui.extensions.implot.ImPlotCond
import cn.enaium.imgui.extensions.implot.ImPlotSpec
import cn.enaium.webrtc.ns.NsLevel
import kotlin.math.log10
import kotlin.math.round

/** Shortest window the slider allows, in milliseconds. */
internal const val MIN_WINDOW_MILLIS = 100

/** Longest window the slider allows, in milliseconds. */
internal const val MAX_WINDOW_MILLIS = 10_000

/** Window the example starts with, in milliseconds. */
internal const val DEFAULT_WINDOW_MILLIS = 1_000

/** Levels the combo offers, in the order of [NsLevel]. */
private val LEVEL_LABELS = arrayOf("6 dB", "12 dB", "18 dB", "21 dB")

/**
 * The example UI: the raw microphone capture and the noise-suppressed output
 * drawn as two ImPlot lines over the same sliding window, with the level
 * selection and what the pipeline reports about the run.
 *
 * All plots share one amplitude scale. A microphone running at speech level is
 * roughly 30 dB below full scale, so a fixed `-1..1` axis would draw a flat
 * line; each plot follows its own level so the quieter output stays readable,
 * and the dBFS readouts in the status keep the absolute values comparable.
 *
 * The window length is a slider: the scopes always hold [MAX_WINDOW_MILLIS] and
 * the plots show the newest part of them.
 */
class WaveformWindow(
    private val pipeline: NsPipeline,
    initialWindowMillis: Int = DEFAULT_WINDOW_MILLIS,
) {

    private val sliderValue = IntArray(1) {
        initialWindowMillis.coerceIn(MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS)
    }

    private val nsEnabled = BooleanArray(1) { pipeline.nsEnabled }

    private val playbackEnabled = BooleanArray(1) { pipeline.playbackEnabled }

    private val levelIndex = IntArray(1) { pipeline.level.ordinal }

    private var windowMillis = sliderValue[0]

    /**
     * Min/max envelopes of the capture and the output: one pair per plot
     * column, written by the scopes and drawn as they are. Sized by the plot
     * width rather than the window, so a ten second window costs no more
     * memory than a short one.
     */
    private var captureEnvelope = FloatArray(0)

    private var outputEnvelope = FloatArray(0)

    /** Amplitude axis of the capture plot, in `-1..1`. */
    private var captureScale = MIN_SCALE

    /** Amplitude axis of the output plot, in `-1..1`. */
    private var outputScale = MIN_SCALE

    /** One frame of the UI. Call between [ImGui.newFrame] and [ImGui.render]. */
    fun draw() {
        // One sweep per scope, and only over what is drawn: the scopes hand back
        // the min/max pairs the plots need, so nothing is copied out and walked
        // a second time on the render thread.
        // The plots are min/max pairs, and ImGui indexes its vertices with 16
        // bits: an anti-aliased polyline costs a handful of vertices per point,
        // so a full-width envelope on a 2712 px display runs past the 65535
        // vertex limit. The cap also keeps the frame cost bounded.
        val columns = minOf(maxOf(ImGui.getIO().displaySize.x.toInt(), MIN_COLUMNS), MAX_COLUMNS)
        if (captureEnvelope.size != columns * 2) {
            captureEnvelope = FloatArray(columns * 2)
            outputEnvelope = FloatArray(columns * 2)
        }
        val samples = windowSamples(windowMillis)
        val step = maxOf((samples + columns - 1) / columns, 1)
        val capturePeak = pipeline.inputScope.scan(step, columns, captureEnvelope)
        val outputPeak = pipeline.outputScope.scan(step, columns, outputEnvelope)

        // Fast attack, slow release: the axis follows a louder passage at once
        // and shrinks back over roughly a second.
        captureScale = maxOf(capturePeak * HEADROOM, captureScale * RELEASE, MIN_SCALE)
        outputScale = maxOf(outputPeak * HEADROOM, outputScale * RELEASE, MIN_SCALE)

        val displaySize = ImGui.getIO().displaySize
        // The window owns the whole viewport: pinned to the top-left corner,
        // resized to the display and stripped of decorations, so it cannot be
        // dragged, resized or collapsed and always fills the SDL window.
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(displaySize, ImGuiCond.ALWAYS)
        if (ImGui.begin("webrtc-ns-kmp waveform", null, WINDOW_FLAGS)) {
            // The control row: no labels in the window, every control carries
            // its explanation as a tooltip and the widgets themselves say what
            // they are. They flow like words in a paragraph, so however narrow
            // the window is, the row wraps instead of clipping.
            newRow()

            placeCheck("NS", nsEnabled) { pipeline.nsEnabled = nsEnabled[0] }
            ImGui.setItemTooltip(
                "Noise suppressor. Switched off, the raw microphone is played back instead - " +
                    "that is what makes the suppression audible.",
            )

            placeCheck("playback", playbackEnabled) { pipeline.playbackEnabled = playbackEnabled[0] }
            ImGui.setItemTooltip("Play the suppressed capture; off mutes the monitor.")

            placeCombo(COMBO_WIDTH) {
                if (ImGui.combo("##level", levelIndex, LEVEL_LABELS)) {
                    pipeline.level = NsLevel.entries[levelIndex[0]]
                }
            }
            ImGui.setItemTooltip(
                "Suppression target. A higher level removes more noise and also more of the " +
                    "low-level detail of the speech.",
            )

            placeSlider(SLIDER_WIDTH) {
                if (ImGui.sliderInt("##window", sliderValue, MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS, "%d ms")) {
                    windowMillis = sliderValue[0]
                }
            }
            ImGui.setItemTooltip("Length of the plotted window.")

            // Everything that is not a control folds away below them: what stays
            // on screen is the controls, and a click on the header shows the
            // devices, the format and the levels.
            if (ImGui.collapsingHeader("status")) status(capturePeak, outputPeak)
            ImGui.separator()

            // The window is fullscreen, so the two plots split whatever height
            // is left instead of leaving the lower part empty.
            val available = ImGui.getContentRegionAvail()
            val plotHeight = maxOf((available.y - PLOT_GAP) / 2f, MIN_PLOT_HEIGHT)
            plot("capture (microphone)", captureEnvelope, step, CAPTURE_COLOR, plotHeight, captureScale)
            plot("output (noise-suppressed)", outputEnvelope, step, OUTPUT_COLOR, plotHeight, outputScale)
        }
        ImGui.end()
    }

    /** Devices, format and levels: everything the window used to show above the controls. */
    private fun status(capturePeak: Float, outputPeak: Float) {
        val captureError = pipeline.captureError
        if (captureError == null) {
            ImGui.text("capture: ${pipeline.captureDeviceName} via ${pipeline.systemName}")
        } else {
            // Android asks for the microphone permission while the app is
            // already running, so the device can appear a moment later.
            ImGui.text("no capture yet, retrying: $captureError")
        }

        val playbackError = pipeline.playbackError
        if (playbackError == null) {
            val dropped = pipeline.droppedPlaybackFrames
            val gaps = pipeline.playbackGaps
            ImGui.text(
                "playback: ${pipeline.playbackDeviceName} via ${pipeline.systemName}" +
                    if (dropped > 0L || gaps > 0L) " (dropped $dropped frames, $gaps gaps)" else "",
            )
        } else {
            ImGui.text("no playback yet, retrying: $playbackError")
        }

        ImGui.text(
            "format: ${pipeline.format}, NS frame: ${pipeline.frameSize} samples (10 ms), " +
                "level ${LEVEL_LABELS[pipeline.level.ordinal]}",
        )
        ImGui.text(
            "processed ${pipeline.processedFrames} frames, clipped ${pipeline.clippedSamples} samples, " +
                "input gaps ${pipeline.captureGaps}",
        )
        ImGui.text(
            "input ${fixed(pipeline.inputDb, 1)} dBFS, output ${fixed(pipeline.outputDb, 1)} dBFS " +
                "(${fixed(pipeline.outputDb - pipeline.inputDb, 1)} dB)",
        )
        ImGui.text(
            "peak: capture ${dbfs(capturePeak)} dBFS, output ${dbfs(outputPeak)} dBFS" +
                " | axis: capture +-${fixed(captureScale.toDouble(), 4)}, " +
                "output +-${fixed(outputScale.toDouble(), 4)}",
        )
        // The suppressor's output is delayed by its overlap-add synthesis, so
        // the two plots are not sample aligned; saying so avoids reading the
        // shift as a processing artefact.
        ImGui.text(
            "the output is delayed by NS's overlap-add synthesis (6 ms at 16 kHz, " +
                "plus the filter bank at 32/48 kHz); the plots are not sample aligned",
        )
    }

    // =========================================================================
    // Layout
    //
    // A row of controls does not fit on a phone (or in a narrow window), and a
    // widget that is cut off at the edge cannot be operated, so the controls go
    // into rows that wrap: every helper below measures what it is about to draw
    // and starts a new row when the rest of the current one is too small. The
    // width of a widget is the frame ImGui draws plus, for a checkbox, the label
    // inside it.
    // =========================================================================

    /** Room the controls already placed on the current row take up. */
    private var rowWidth = 0f

    /** Starts a new control row. */
    private fun newRow() {
        rowWidth = 0f
    }

    /** Reserves [width] on the current row, wrapping onto a new one when it does not fit. */
    private fun reserve(width: Float) {
        val available = ImGui.getContentRegionAvail().x
        val needed = if (rowWidth > 0f) ITEM_GAP + width else width
        if (rowWidth > 0f && rowWidth + needed > available) {
            // The cursor is already on the next line, an item leaves it there.
            rowWidth = width
        } else {
            if (rowWidth > 0f) {
                ImGui.sameLine()
                rowWidth += ITEM_GAP
            }
            rowWidth += width
        }
    }

    /** A checkbox: the check square plus the label it carries. */
    private fun placeCheck(label: String, value: BooleanArray, onChange: () -> Unit) {
        reserve(ImGui.getFrameHeight() + LABEL_GAP + ImGui.calcTextSize(label).x)
        if (ImGui.checkbox(label, value)) onChange()
    }

    /** A slider [itemWidth] wide; what it does is in its tooltip. */
    private fun placeSlider(itemWidth: Float, block: () -> Unit) {
        reserve(itemWidth)
        ImGui.setNextItemWidth(itemWidth)
        block()
    }

    /** A dropdown [itemWidth] wide. */
    private fun placeCombo(itemWidth: Float, block: () -> Unit) {
        reserve(itemWidth)
        ImGui.setNextItemWidth(itemWidth)
        block()
    }

    /**
     * Draws one envelope. Every pair of points covers [step] samples, which is
     * what the x scale is derived from, and the cost of the line is bounded by
     * the plot width instead of the window length.
     */
    private fun plot(title: String, envelope: FloatArray, step: Int, color: ImVec4, height: Float, scale: Float) {
        if (!ImPlot.beginPlot(title, ImVec2(-1f, height))) return
        ImPlot.setupAxes("milliseconds", "amplitude", ImPlotAxisFlags.NONE, ImPlotAxisFlags.NONE)
        // The window scrolls, so the x axis is pinned to the full sweep.
        ImPlot.setupAxesLimits(
            0.0,
            windowMillis.toDouble(),
            -scale.toDouble(),
            scale.toDouble(),
            ImPlotCond.ALWAYS,
        )
        ImPlot.plotLine(
            "##$title",
            envelope,
            xScale = step / 2.0 * MILLIS_PER_SAMPLE,
            spec = ImPlotSpec(lineColor = color, lineWeight = 1f),
        )
        ImPlot.endPlot()
    }

    private fun dbfs(value: Float): String =
        if (value <= 0f) "-inf" else fixed(20.0 * log10(value.toDouble()), 1)

    private companion object {
        /** Fullscreen host window: no title bar, nothing to drag or resize. */
        const val WINDOW_FLAGS = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_COLLAPSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
            ImGuiWindowFlags.NO_NAV_FOCUS

        /** Space kept between the two plots. */
        val PLOT_GAP: Float get() = ImGui.getFontSize() * 0.5f

        /** Floor for very small windows. */
        val MIN_PLOT_HEIGHT: Float get() = ImGui.getFontSize() * 4f

        /** Guarantees the envelope has at least one column to fill. */
        const val MIN_COLUMNS = 1

        /**
         * Upper bound on the envelope width. 1024 columns is 2048 points per
         * plot - a few thousand vertices even with anti-aliasing, so two plots
         * stay well inside ImGui's 16 bit index range - and still resolves a
         * 2.6 px wide column on a 2712 px display.
         */
        const val MAX_COLUMNS = 1024

        /**
         * Widths the flow reserves for a control, and the gap kept between two.
         * Relative to the font, which is baked at the host's UI scale: a slider
         * that fits "384 ms" is the same fraction of a line either way.
         */
        val SLIDER_WIDTH: Float get() = ImGui.getFontSize() * 8f

        val COMBO_WIDTH: Float get() = ImGui.getFontSize() * 8f

        /**
         * Space between two controls of a row and between a checkbox's square and
         * its label: the style's own spacing, which [pushScaledStyle] scales.
         */
        val ITEM_GAP: Float get() = ImGui.getFontSize() * 8f / 13f

        val LABEL_GAP: Float get() = ImGui.getFontSize() * 4f / 13f

        /** Keeps the peaks below the top of the axis. */
        const val HEADROOM = 1.15f

        /** Per-frame release factor of the amplitude scale. */
        const val RELEASE = 0.97f

        const val MIN_SCALE = 1e-4f

        val CAPTURE_COLOR = ImVec4(0.95f, 0.45f, 0.35f, 1f)

        val OUTPUT_COLOR = ImVec4(0.35f, 0.85f, 0.5f, 1f)
    }
}

/** Samples a window of [millis] holds at the example's fixed 48 kHz. */
internal fun windowSamples(millis: Int): Int = millis * SAMPLE_RATE / 1000

/** One sample expressed in milliseconds, for the time axis. */
private const val MILLIS_PER_SAMPLE = 1000.0 / SAMPLE_RATE

/**
 * Formats [value] with [decimals] digits after the point. `String.format` is
 * JVM only, and the example is shared with the native targets.
 */
internal fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "nan"
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(value * factor).toLong()
    val magnitude = if (scaled < 0) -scaled else scaled
    val whole = magnitude / factor
    val fraction = (magnitude % factor).toString().padStart(decimals, '0')
    return "${if (scaled < 0) "-" else ""}$whole.$fraction"
}
