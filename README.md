# webrtc-ns-kmp

Kotlin Multiplatform bindings for the [WebRTC noise suppressor](https://github.com/Enaium/webrtc-ns) — the `modules/audio_processing/ns` module extracted from WebRTC and packaged as a standalone library. The suppressor removes stationary background noise (fans, hum, road noise, ...) from a 10 ms microphone frame before it is sent or recorded.

## Supported Platforms

| Platform       | Targets                                                     | Mechanism                                  |
| -------------- | ----------------------------------------------------------- | ------------------------------------------ |
| **Android**    | arm64-v8a, armeabi-v7a, x86, x86_64                          | JNI (shared library via CMake)             |
| **Android (Kotlin/Native)** | arm64-v8a, armeabi-v7a, x86, x86_64             | Kotlin/Native cinterop (static library)    |
| **JVM**        | Linux x86_64/aarch64, macOS arm64/x86_64, Windows x86_64     | JNI (per-OS/arch JAR resource, auto-extracted by `NativeLoader`) |
| **iOS**        | arm64, x64, simulatorArm64                                   | Kotlin/Native cinterop (static library)    |
| **macOS**      | arm64, x86_64                                                | Kotlin/Native cinterop (static library)    |
| **Linux**      | x86_64                                                       | Kotlin/Native cinterop (static library)    |
| **Windows**    | mingwX64                                                     | Kotlin/Native cinterop (bindings)          |
| **tvOS**       | arm64, simulatorArm64                                        | Kotlin/Native cinterop (static library)    |
| **watchOS**    | arm64, simulatorArm64, deviceArm64                           | Kotlin/Native cinterop (static library)    |

## Gradle Dependency

**Kotlin Multiplatform / Android:**

```kotlin
implementation("cn.enaium.webrtc.ns:webrtc-ns-kmp:1.0.0-rolling.pRJneQThJ")
```

> Built with Kotlin 2.4.10: consumers need a Kotlin 2.4+ compiler, since older ones cannot read the 2.4 metadata of the published artifacts.

**JVM:** the right native binary is resolved automatically — the `webrtc-ns-kmp-jvm` artifact pulls in the matching `:jni-jvm-*` sibling on the classpath:

- `webrtc-ns-kmp-jni-jvm-linux-x86_64`
- `webrtc-ns-kmp-jni-jvm-linux-aarch64`
- `webrtc-ns-kmp-jni-jvm-darwin-x86_64`
- `webrtc-ns-kmp-jni-jvm-darwin-aarch64`
- `webrtc-ns-kmp-jni-jvm-windows-x86_64`

`NativeLoader` detects `os.name`/`os.arch` at runtime, extracts the matching binary from the classpath to a temp directory, and `System.load`s it. No `java.library.path` setup is required for downstream JVM consumers.

## Quick Start

```kotlin
import cn.enaium.webrtc.ns.NsLevel
import cn.enaium.webrtc.ns.createNoiseSuppressor

// 48 kHz mono, 12 dB suppression target
val ns = createNoiseSuppressor(48000, 1, NsLevel.DB12)

// One 10 ms frame, interleaved, 480 samples per channel
val frame = FloatArray(480)

// Samples are in the int16 range ([-32768, 32767]), not [-1, 1].
// A normalized buffer is scaled on the way in and back out:
for (i in frame.indices) frame[i] = pcm16[i].toFloat()

ns.analyze(frame)           // before AEC/AGC if those are used
// ... AEC/AGC may modify frame here ...
ns.process(frame)           // in-place noise suppression

for (i in frame.indices) pcm16[i] = frame[i].toInt().toShort()

ns.close()
```

`analyze` must be called before `process` for the same frame. Instances are not thread-safe; one suppressor per audio thread.

## API Reference

```kotlin
enum class NsLevel { DB6, DB12, DB18, DB21 }

interface NoiseSuppressor : AutoCloseable {
    val sampleRateHz: Int
    val numChannels: Int
    val numFramesPerChannel: Int

    fun analyze(interleaved: FloatArray)
    fun process(interleaved: FloatArray)
    fun setCaptureOutputUsage(captureOutputUsed: Boolean)
}

fun createNoiseSuppressor(
    sampleRateHz: Int,
    numChannels: Int,
    level: NsLevel = NsLevel.DB12,
): NoiseSuppressor

fun noiseSuppressorVersion(): String
```

| Member | Description |
| ------ | ----------- |
| `analyze(interleaved)` | Analyses one frame without modifying it |
| `process(interleaved)` | Applies noise suppression in place |
| `setCaptureOutputUsage(used)` | Lets the suppressor skip output processing while muted; `process` then leaves the buffer untouched |
| `numFramesPerChannel` | `sampleRateHz / 100`: 160, 320 or 480 samples |

`sampleRateHz` must be 16000, 32000 or 48000 Hz. `interleaved` holds exactly `numFramesPerChannel * numChannels` samples in frame-major order (`[frame0_ch0, frame1_ch0, ...]`), in the int16 range.

### Output latency

The suppressor's output is delayed by its overlap-add synthesis: 6 ms at 16 kHz, plus the analysis/synthesis filter-bank delay at 32/48 kHz. A streaming consumer can leave this constant delay in place or compensate it when aligning the output against another signal.

### Quality and sample rate

16 kHz is the rate WebRTC's noise suppressor is built around, and it gives the best result. At 32/48 kHz the suppressor splits each 10 ms frame into 2/3 bands of 16 kHz with a filter bank before processing it; that filter bank in the standalone extraction is not perfect reconstruction, so part of the noise reduction is traded for broadband error. Measured on the fixture recording in [`webrtc-ns/tests`](webrtc-ns/tests) at 6 dB: the SNR improves by 3.0 dB at 16 kHz, but only by 0.4 dB at 48 kHz.

For voice use 16 kHz. 48 kHz is supported and no longer adds the static caused by advancing the filter bank twice per frame (the binding drives `NoiseSuppressor` directly so each filter bank sees exactly one frame per call), but the band filter bank still limits how much of the improvement survives.

## Examples

### [`examples/basic`](examples/basic/) — Android Compose demo

An Android app with a Jetpack Compose UI:

- **Noise suppression switch** — toggle NS on/off, which makes the difference audible
- **Suppression level chips** — 6, 12, 18 or 21 dB, rebuilt on the audio thread
- **Playback switch** — mute the monitor
- **Start/Stop button** — real-time `AudioRecord → NS → AudioTrack` loopback at 48 kHz mono

It captures the microphone, suppresses each 10 ms frame and plays the result back through the speaker. Its instrumented test denoises `noisy.wav` from the submodule's fixtures and compares the result with `clean.wav`.

### [`examples/waveform`](examples/waveform/) — live waveform viewer

A Kotlin Multiplatform app (JVM desktop, macOS/Linux/Windows native executables and Android via [`examples/waveform/android`](examples/waveform/android/)) that draws the NS signals with Dear ImGui + ImPlot in an SDL3 window:

- **Two traces** — the raw microphone capture and the noise-suppressed output, over a 100 ms–10 s sliding window
- **SDL_GPU rendering** — the window is drawn with the imgui-kmp SDL_GPU backend (Vulkan on Android and desktop, Metal on macOS); where SDL_GPU is unavailable (headless CI runs on SDL's dummy driver) the log says so and the 2D renderer takes over
- **Audible result** — the suppressed capture is played through the output device, so the attenuation can be heard and not just seen; playback is toggled from the UI
- **NS controls** — NS on/off, suppression level and window length
- **Levels** — input/output dBFS of the last second, clipping, device drops and gaps

```bash
./gradlew :examples:waveform:jvmRun                  # desktop JVM
./gradlew :examples:waveform:android:assembleDebug   # Android APK
```

Native executables (`./examples/waveform/build/bin/<target>/<mode>Executable/waveform.kexe`) take `NS_KMP_FRAMES` to bound a headless run; the JVM entry takes `--frames N`.

## Building from Source

### Prerequisites

- JDK 21+
- CMake 3.16+
- Android SDK + NDK (for Android targets)
- Xcode command-line tools (for iOS/macOS/tvOS/watchOS targets)
- MinGW-w64 (for Windows targets from Linux/Windows)

### Clone with submodules

```bash
git clone --recursive https://github.com/Enaium/webrtc-ns-kmp.git
cd webrtc-ns-kmp
```

### Publish to Maven Local

```bash
./gradlew :ns:publishToMavenLocal
```

### Run tests

```bash
./gradlew :ns:jvmTest        # JVM (JNI), compares against the WAV fixtures
./gradlew :ns:macosArm64Test # macOS native (cinterop + static library)
```

## Project Structure

```
webrtc-ns-kmp/
├── webrtc-ns/                # Git submodule (C++ library)
├── jni/
│   ├── CMakeLists.txt        # JNI shared library build
│   ├── jni_bridge.cpp        # JNI bridge (C++ → JVM/Android)
│   ├── c_api/                # C API (webrtc_ns_c.h/.cc)
│   └── jvm/                  # Per-OS/arch JNI publication subprojects
│       ├── darwin-aarch64, darwin-x86_64
│       ├── linux-x86_64, linux-aarch64
│       └── windows-x86_64
├── ns/                       # Kotlin Multiplatform module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/       # expect declarations + common interfaces
│       ├── commonTest/
│       ├── jvmMain/          # JVM actual (JNI) + NativeLoader
│       ├── androidMain/      # Android actual (JNI)
│       ├── nativeMain/       # Native actual (cinterop)
│       └── nativeInterop/cinterop/
├── examples/
│   ├── basic/                # Android Compose demo (loopback + NS switch)
│   └── waveform/             # KMP ImGui/ImPlot waveform viewer
│       └── android/          # APK wrapping the androidNative libmain.so
└── .github/workflows/        # publish + test
```

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file. The bundled WebRTC NS sources are BSD-3-Clause; see [`webrtc-ns/LICENSE`](webrtc-ns/LICENSE).
