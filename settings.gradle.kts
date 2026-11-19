pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // The Android example consumes webrtc-ns-kmp from the local Maven
        // repository, where the test workflow publishes the AAR before it is
        // assembled. It is listed first so that build always uses the artifact
        // built from this checkout.
        mavenLocal()
        google()
        mavenCentral()
        // The Kotlin JS plugin resolves its Node.js distribution from here.
        // PREFER_SETTINGS keeps project-level repositories out of the way, so
        // the distribution has to be declared next to the Maven ones.
        ivy("https://nodejs.org/dist") {
            name = "NodeDistributions"
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        // Kotlin/Wasm links its executables with binaryen, which the plugin
        // fetches from the upstream GitHub release.
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "BinaryenDistributions"
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "webrtc-ns-kmp"

include(":ns")
include(":examples:basic")
include(":examples:waveform")
include(":examples:waveform:android")

// Per-OS/arch JNI artifacts that bundle the prebuilt libwebrtc_ns_jni shared
// library as a classpath resource. NativeLoader extracts the matching one at
// runtime.
listOf(
    "linux-x86_64",
    "linux-aarch64",
    "darwin-x86_64",
    "darwin-aarch64",
    "windows-x86_64",
).forEach { classifier ->
    val name = ":jni-jvm-$classifier"
    include(name)
    project(name).projectDir = file("jni/jvm/$classifier")
}
