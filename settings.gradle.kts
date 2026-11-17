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
