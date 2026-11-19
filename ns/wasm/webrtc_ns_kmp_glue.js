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
 *   - a frame is a Float32Array: the glue copies it into one reusable
 *     Emscripten heap buffer, calls the shim, and (for process) copies the
 *     processed frame back;
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

    // One heap buffer serves every frame the page processes; growing it once
    // is what keeps a 10 ms audio callback free of malloc/free traffic.
    let framePtr = 0;
    let frameCapacity = 0;

    /* Copies a frame into the reusable heap buffer and returns its byte offset. */
    const pushFrame = (samples) => {
        const floats = samples.length;
        if (floats > frameCapacity) {
            if (framePtr) Module._free(framePtr);
            framePtr = Module._malloc(floats * 4);
            if (!framePtr) throw new Error('webrtc-ns: out of wasm memory');
            frameCapacity = floats;
        }
        Module.HEAPF32.set(samples, framePtr >> 2);
        return framePtr;
    };

    globalThis.webrtcNsKmpCreate = (rate, channels, level) => create(rate, channels, level);
    globalThis.webrtcNsKmpDestroy = (handle) => destroy(handle);
    globalThis.webrtcNsKmpAnalyze = (handle, samples, numFrames) => {
        const ptr = pushFrame(samples);
        return analyze(handle, ptr, numFrames);
    };
    globalThis.webrtcNsKmpProcess = (handle, samples, numFrames) => {
        const ptr = pushFrame(samples);
        const ok = process(handle, ptr, numFrames);
        // The heap may have grown during the call, so re-read HEAPF32 after it
        // before copying the result back.
        samples.set(Module.HEAPF32.subarray(ptr >> 2, (ptr >> 2) + samples.length));
        return ok;
    };
    globalThis.webrtcNsKmpSetCaptureOutputUsage = (handle, used) =>
        setCaptureOutputUsage(handle, used ? 1 : 0);
    globalThis.webrtcNsKmpSampleRateHz = (handle) => sampleRateHz(handle);
    globalThis.webrtcNsKmpNumChannels = (handle) => numChannels(handle);
    globalThis.webrtcNsKmpNumFramesPerChannel = (handle) => numFramesPerChannel(handle);
    globalThis.webrtcNsKmpVersion = () => version();

    return Module;
}
