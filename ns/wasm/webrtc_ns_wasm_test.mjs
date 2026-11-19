/*
 * Headless check of the Wasm pipeline: Emscripten module -> JS glue ->
 * webrtc-ns-kmp. Run after `./gradlew :ns:assembleWasmNpm`:
 *
 *   node ns/wasm/webrtc_ns_wasm_test.mjs
 *
 * It feeds deterministic noise through both analyze() and process(), checks
 * that the stationary noise is attenuated, and that a bypassed frame is left
 * untouched.
 */

import { initWebrtcNsKmp } from '../build/npm/webrtc_ns_kmp_glue.js';

await initWebrtcNsKmp();

const version = webrtcNsKmpVersion();
if (!version) throw new Error('empty webrtc_ns_wasm_version()');

const rate = 16000;
const channels = 1;
const frames = rate / 100;
const suppressor = webrtcNsKmpCreate(rate, channels, 0 /* 6 dB */);
if (!suppressor) throw new Error('webrtcNsKmpCreate returned 0');

if (webrtcNsKmpSampleRateHz(suppressor) !== rate) throw new Error('sample rate mismatch');
if (webrtcNsKmpNumChannels(suppressor) !== channels) throw new Error('channel count mismatch');
if (webrtcNsKmpNumFramesPerChannel(suppressor) !== frames) throw new Error('frame size mismatch');

const frame = new Float32Array(frames);
let seed = 0x5eed;
let inputPower = 0;
let outputPower = 0;

for (let f = 0; f < 250; f++) {
    for (let i = 0; i < frames; i++) {
        seed = (Math.imul(seed, 1103515245) + 12345) >>> 0;
        // int16-range noise, the range the suppressor works in
        frame[i] = ((seed >>> 16) & 0x7fff) - 16384;
        if (f >= 150) inputPower += frame[i] * frame[i];
    }
    if (webrtcNsKmpAnalyze(suppressor, frame, frames) === 0) throw new Error('analyze failed');
    if (webrtcNsKmpProcess(suppressor, frame, frames) === 0) throw new Error('process failed');
    for (let i = 0; i < frames; i++) {
        if (!Number.isFinite(frame[i])) throw new Error('non-finite output sample');
        if (f >= 150) outputPower += frame[i] * frame[i];
    }
}

webrtcNsKmpSetCaptureOutputUsage(suppressor, false);
for (let i = 0; i < frames; i++) frame[i] = i;
const before = Array.from(frame);
webrtcNsKmpAnalyze(suppressor, frame, frames);
webrtcNsKmpProcess(suppressor, frame, frames);
for (let i = 0; i < frames; i++) {
    if (frame[i] !== before[i]) throw new Error('bypassed frame was modified');
}

webrtcNsKmpDestroy(suppressor);

const ratio = outputPower / inputPower;
console.log(
    `${version}: wasm ${rate} Hz/${channels} ch, ` +
        `last 100 frames output/input power ${(10 * Math.log10(ratio)).toFixed(1)} dB`,
);
if (!(ratio < 0.8)) {
    throw new Error(`stationary noise was not attenuated (power ratio ${ratio})`);
}
if (webrtcNsKmpVersion() !== version) throw new Error('version changed between calls');
console.log('wasm NS test passed');
