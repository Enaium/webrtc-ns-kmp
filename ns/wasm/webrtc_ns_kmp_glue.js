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

/*
 * Glue between the Emscripten-compiled WebRTC NS module (webrtc_ns_wasm.js)
 * and Kotlin/Wasm. Instantiates the module and exposes every
 * webrtc_ns_wasm_* shim function as a global so Kotlin's external declarations
 * can call them.
 *
 * Marshaling conventions (see webrtc_ns_wasm_shim.c):
 *   - the suppressor handle is an int; scalars are passed straight through;
 *   - a frame is a Float32Array: the glue allocates an Emscripten heap buffer,
 *     copies the array into it, calls the shim, and (for process) copies the
 *     processed frame back before freeing the buffer;
 *   - the module is initialized asynchronously, so the host has to await
 *     initWebrtcNsKmp() before creating a suppressor from Kotlin.
 */

export async function initWebrtcNsKmp(options = {}) {
    const mod = await import('./webrtc_ns_wasm.js');
    const loadNs = typeof mod.default === 'function' ? mod.default : mod;
    const Module = await loadNs(options);

    const C = Module.cwrap;

    const create = C('webrtc_ns_wasm_create', 'number', ['number', 'number', 'number']);
    const destroy = C('webrtc_ns_wasm_destroy', null, ['number']);
    const analyze = C('webrtc_ns_wasm_analyze', 'number', ['number', 'number', 'number']);
    const process = C('webrtc_ns_wasm_process', 'number', ['number', 'number', 'number']);
    const setCaptureOutputUsage = C('webrtc_ns_wasm_set_capture_output_usage', null, ['number', 'number']);
    const sampleRateHz = C('webrtc_ns_wasm_sample_rate_hz', 'number', ['number']);
    const numChannels = C('webrtc_ns_wasm_num_channels', 'number', ['number']);
    const numFramesPerChannel = C('webrtc_ns_wasm_num_frames_per_channel', 'number', ['number']);
    const version = C('webrtc_ns_wasm_version', 'string', []);

    /* Copies a frame into the heap and returns its byte offset. */
    const pushFrame = (samples) => {
        const ptr = Module._malloc(samples.length * 4);
        if (!ptr) throw new Error('webrtc-ns: out of wasm memory');
        Module.HEAPF32.set(samples, ptr >> 2);
        return ptr;
    };

    globalThis.webrtcNsKmpCreate = (rate, channels, level) => create(rate, channels, level);
    globalThis.webrtcNsKmpDestroy = (handle) => destroy(handle);
    globalThis.webrtcNsKmpAnalyze = (handle, samples, numFrames) => {
        const ptr = pushFrame(samples);
        try {
            return analyze(handle, ptr, numFrames);
        } finally {
            Module._free(ptr);
        }
    };
    globalThis.webrtcNsKmpProcess = (handle, samples, numFrames) => {
        const ptr = pushFrame(samples);
        try {
            const ok = process(handle, ptr, numFrames);
            // The heap may have grown during the call, so re-read HEAPF32
            // after it before copying the result back.
            samples.set(Module.HEAPF32.subarray(ptr >> 2, (ptr >> 2) + samples.length));
            return ok;
        } finally {
            Module._free(ptr);
        }
    };
    globalThis.webrtcNsKmpSetCaptureOutputUsage = (handle, used) =>
        setCaptureOutputUsage(handle, used ? 1 : 0);
    globalThis.webrtcNsKmpSampleRateHz = (handle) => sampleRateHz(handle);
    globalThis.webrtcNsKmpNumChannels = (handle) => numChannels(handle);
    globalThis.webrtcNsKmpNumFramesPerChannel = (handle) => numFramesPerChannel(handle);
    globalThis.webrtcNsKmpVersion = () => version();

    return Module;
}
