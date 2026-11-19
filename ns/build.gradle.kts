import java.util.Properties
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

val jniDir = rootProject.projectDir.resolve("jni")
val webrtcNsDir = rootProject.projectDir.resolve("webrtc-ns")

val hostOs = OperatingSystem.current()

// ==================== Android NDK resolution ====================
// Resolved before canBuildNativeTarget (and therefore before the kotlin {}
// block) so that the androidNative targets can embed the NDK-cross-compiled
// static library on hosts that have the NDK installed.
val androidApiLevel = 21

val pinnedAndroidNdkVersion = "27.0.12077973"

fun resolveAndroidSdkDir(): java.io.File? {
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val localProps = rootProject.file("local.properties")
    if (localProps.isFile) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    return null
}

fun resolveAndroidNdkDir(): java.io.File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val sdk = resolveAndroidSdkDir() ?: return null
    val ndkParent = sdk.resolve("ndk")
    if (!ndkParent.isDirectory) return null
    val pinned = ndkParent.resolve(pinnedAndroidNdkVersion)
    if (pinned.isDirectory) return pinned
    return ndkParent.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

val resolvedAndroidNdk = resolveAndroidNdkDir()
val androidNdkToolchain = resolvedAndroidNdk?.resolve("build/cmake/android.toolchain.cmake")

// Whether the current host can cross-compile the C library for the given
// Kotlin/Native target. Apple targets build from macOS via Xcode; linuxX64 and
// mingwX64 build on Linux hosts (mingwX64 via the MinGW cross-compiler, which
// matches Kotlin/Native's own MinGW linker).
fun canBuildNativeTarget(targetName: String): Boolean {
    return when {
        hostOs.isMacOsX && targetName.startsWith("macos") -> true
        hostOs.isMacOsX && targetName.startsWith("ios") -> true
        hostOs.isMacOsX && targetName.startsWith("tvos") -> true
        hostOs.isMacOsX && targetName.startsWith("watchos") -> true
        hostOs.isLinux && (targetName == "linuxX64" || targetName == "mingwX64") -> true
        // Android native (Kotlin/Native) static libraries are cross-compiled
        // with the NDK toolchain, which any host with the NDK can run.
        targetName.startsWith("androidNative") && androidNdkToolchain?.isFile == true -> true
        else -> false
    }
}

// Resolved once in the root build (which also covers the Android SDK's copy,
// for machines that have no standalone cmake).
val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

kotlin {
    // ==================== JVM ====================
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // ==================== Android ====================
    android {
        namespace = "cn.enaium.webrtc.ns"
        compileSdk = 37
        minSdk = 21

        withHostTest {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // ==================== WebAssembly ====================
    // Kotlin/Wasm has no cinterop, so the wasmJs actuals drive a standalone
    // Emscripten module through a JS glue layer; see the wasm section below
    // and ns/wasm/.
    wasmJs {
        // A JS environment is required for the npm tooling even though this is
        // a library: the example installs the Emscripten runtime from npm.
        browser()
    }

    // ==================== Native ====================
    macosArm64()
    macosX64()

    linuxX64()

    mingwX64()

    iosArm64()
    iosX64()
    iosSimulatorArm64()

    tvosArm64()
    tvosSimulatorArm64()

    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    // Android native (Kotlin/Native) targets, used by Android apps that link a
    // libmain.so instead of the JVM AAR (examples/waveform/android does).
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // ==================== cinterop for all native targets ====================
    targets.withType<KotlinNativeTarget> {
        val targetName = this.name
        val canBuild = canBuildNativeTarget(targetName)
        compilations.getByName("main") {
            cinterops {
                create("webrtcNs") {
                    defFile(project.file("src/nativeInterop/cinterop/webrtc_ns.def"))
                    includeDirs(
                        project.file("src/nativeInterop/cinterop"),
                        rootProject.file("jni/c_api"),
                    )
                    if (canBuild) {
                        // Embed the per-target static library into the produced
                        // cinterop klib. Targets that can't be built on this host
                        // still get bindings (for klib publishing); the static
                        // library is built and embedded when building on the
                        // matching host.
                        val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
                        extraOpts(
                            "-libraryPath", outputDir.absolutePath,
                            "-staticLibrary", "libwebrtc_ns.a",
                        )
                    }
                }
            }
            defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")
        }
    }

    // ==================== Source sets ====================
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Bundle all five JNI artifacts so consumers get the right
                // native binary out of the box; NativeLoader picks one at
                // runtime by os.name/os.arch.
                runtimeOnly(project(":jni-jvm-linux-x86_64"))
                runtimeOnly(project(":jni-jvm-linux-aarch64"))
                runtimeOnly(project(":jni-jvm-darwin-x86_64"))
                runtimeOnly(project(":jni-jvm-darwin-aarch64"))
                runtimeOnly(project(":jni-jvm-windows-x86_64"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        getByName("androidMain") {
            // Share the same JNI source code with JVM. On Android the
            // NativeLoader falls back to System.loadLibrary since the .so is
            // bundled inside the AAR's jniLibs.
            kotlin.srcDir("src/jvmMain/kotlin")
        }

        getByName("wasmJsMain") {
            // The repository disables the automatic default hierarchy
            // template, so the leaf source sets are wired up explicitly.
            dependsOn(getByName("commonMain"))
            dependencies {
                // org.khronos.webgl typed arrays used to marshal frames across
                // the JS bridge into the Emscripten module.
                implementation(libs.kotlinx.browser)
            }
        }
    }
}

// ==================== Desktop JVM ====================
// The JNI library is provided by the :jni-jvm-{linux,darwin}-{x86_64,aarch64}
// subprojects. Their JARs ship libwebrtc_ns_jni as a classpath resource that
// NativeLoader extracts at runtime - so :jvmTest needs no java.library.path
// tweak.
//
// Android host-test targets (Android unit tests running on the JVM) call
// System.loadLibrary directly from NoiseSuppressor.jvm.kt, so they still need a
// flat directory containing libwebrtc_ns_jni.{so,dylib}. We reuse the host's
// :jni-jvm-* subproject build output for that.
val hostJniProjectName = run {
    val arch = System.getProperty("os.arch").lowercase()
    val os = OperatingSystem.current()
    val archClassifier = when (arch) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> null
    }
    val osClassifier = when {
        os.isLinux -> "linux"
        os.isMacOsX -> "darwin"
        os.isWindows -> "windows"
        else -> null
    }
    if (osClassifier != null && archClassifier != null) {
        ":jni-jvm-$osClassifier-$archClassifier"
    } else {
        null
    }
}

if (hostJniProjectName != null) {
    val hostJniProject = project(hostJniProjectName)
    val hostNativeDir = hostJniProject.layout.buildDirectory.dir(
        "jni-native/${hostJniProjectName.removePrefix(":jni-jvm-")}",
    )

    tasks.withType<Test>().configureEach {
        if (name.contains("AndroidHostTest", ignoreCase = true)) {
            dependsOn("$hostJniProjectName:buildJniLibrary")
            systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
        }
    }
}

// JVM tests read the WAV fixtures from the webrtc-ns submodule at the repo root.
tasks.withType<Test>().configureEach {
    workingDir = rootDir
}

// Kotlin/Native tests run in the module's build dir; point them at the repo root
// so they can reach webrtc-ns/tests/*.wav.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    workingDir = rootDir.absolutePath
}

// ==================== Native: build static C library for each target ====================
fun registerNativeBuildTasks(targetName: String, cmakeFlags: List<String> = emptyList()) {
    val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-$targetName").get().asFile

    val configureTask = tasks.register<Exec>("configureNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.mkdirs()
            if (targetName == "mingwX64" && hostOs.isLinux) {
                // WebRTC includes <Windows.h> (capital W), but on Linux the
                // mingw-w64 header is windows.h (lowercase) and the
                // case-sensitive filesystem cannot resolve it. Create an alias
                // symlink in the mingw include dir (needs root).
                val includeDir = File("/usr/x86_64-w64-mingw32/include")
                val lower = File(includeDir, "windows.h")
                val upper = File(includeDir, "Windows.h")
                if (lower.isFile && !upper.exists()) {
                    val proc = ProcessBuilder("sudo", "ln", "-s", "windows.h", upper.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { logger.lifecycle(it) }
                    }
                    proc.waitFor()
                }
            }
        }
        workingDir = cmakeBuildDir
        commandLine(
            listOf(
                cmakeExecutable, jniDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_JNI=OFF",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${outputDir.absolutePath}",
            ) + cmakeFlags,
        )
    }

    val buildTask = tasks.register<Exec>("buildNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    }

    tasks.matching {
        it.name.startsWith("cinteropWebrtcNs") &&
            it.name.endsWith(targetName.replaceFirstChar { c -> c.uppercase() })
    }.configureEach {
        dependsOn(buildTask)
    }
}

if (hostOs.isMacOsX) {
    registerNativeBuildTasks(
        "macosArm64",
        listOf(
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        ),
    )
    registerNativeBuildTasks(
        "macosX64",
        listOf(
            "-DCMAKE_OSX_ARCHITECTURES=x86_64",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
        ),
    )
    registerNativeBuildTasks(
        "iosArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=iphoneos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "iosX64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
            "-DCMAKE_OSX_ARCHITECTURES=x86_64",
            "-DCMAKE_OSX_SYSROOT=iphonesimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "iosSimulatorArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=iphonesimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "tvosArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=tvOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=appletvos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "tvosSimulatorArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=tvOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=appletvsimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "watchosArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=watchOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=watchos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
        ),
    )
    registerNativeBuildTasks(
        "watchosSimulatorArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=watchOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=watchsimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
        ),
    )
    registerNativeBuildTasks(
        "watchosDeviceArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=watchOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=watchos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
        ),
    )
} else if (hostOs.isLinux) {
    registerNativeBuildTasks("linuxX64")
    // mingwX64 is cross-compiled with MinGW-w64; install it with
    // `sudo apt-get install gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64`.
    // CMAKE_SYSTEM_NAME=Windows makes CMake cross-compile; the MinGW sysroot
    // at /usr/x86_64-w64-mingw32 provides Windows.h and the CRT.
    registerNativeBuildTasks(
        "mingwX64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=Windows",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
            "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc",
            "-DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++",
            "-DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres",
        ),
    )
}

// ==================== Android native (Kotlin/Native) static libraries ====================
// Cross-compiled with the NDK toolchain for the androidNative targets; the
// resulting libwebrtc_ns.a is embedded into each target's cinterop klib, so a
// Kotlin/Native Android app (libmain.so) links WebRTC NS statically.
androidNdkToolchain?.takeIf { it.isFile }?.let { toolchain ->
    listOf(
        "androidNativeArm64" to "arm64-v8a",
        "androidNativeArm32" to "armeabi-v7a",
        "androidNativeX64" to "x86_64",
        "androidNativeX86" to "x86",
    ).forEach { (targetName, abi) ->
        registerNativeBuildTasks(
            targetName,
            listOf(
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-$androidApiLevel",
            ),
        )
    }
}

// ==================== Android: build JNI shared library per ABI ====================
val androidJniAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

val androidJniLibsDir = layout.buildDirectory.dir("jniLibs")

val buildAndroidJniLibs by tasks.registering {
    group = "build"
    description = "Builds the JNI shared library for all Android ABIs."
}

androidJniAbis.forEach { abi ->
    val outputDir = layout.buildDirectory.dir("jniLibs/$abi")
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-android-$abi").get().asFile

    val configureTask = tasks.register<Exec>("configureAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.get().asFile.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            cmakeExecutable, jniDir.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_TOOLCHAIN_FILE=${androidNdkToolchain?.absolutePath ?: ""}",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-$androidApiLevel",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outputDir.get().asFile.absolutePath}",
        )
    }

    val buildTask = tasks.register<Exec>("buildAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    }

    buildAndroidJniLibs.configure { dependsOn(buildTask) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            androidJniLibsDir.get().asFile.absolutePath,
        )
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(buildAndroidJniLibs) }

// ==================== wasm: build the NS module for the wasmJs target ====================
// Kotlin/Wasm has no cinterop and cannot embed C libraries, so NS is compiled
// to a standalone Emscripten module (webrtc_ns_wasm.js + .wasm) that the
// wasmJs actuals drive through a JS glue layer (see ns/wasm/).

fun emsdkPath(): String {
    System.getenv("EMSDK")?.takeIf { it.isNotBlank() }?.let { return it }
    providers.gradleProperty("wasm.emsdk").orNull?.takeIf { it.isNotBlank() }?.let { return it }
    val home = System.getProperty("user.home")
    listOf(File(home, "emsdk"), File(home, "sdk/emsdk"))
        .forEach { if (it.isDirectory()) return it.absolutePath }
    return ""
}

val wasmEmsdk = emsdkPath()
val wasmEmcc = File(wasmEmsdk, "upstream/emscripten/emcc")
val wasmEmxx = File(wasmEmsdk, "upstream/emscripten/em++")
val wasmToolchain = File(wasmEmsdk, "upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake")
val wasmSrcDir = projectDir.resolve("wasm")
val wasmOutputDir = layout.buildDirectory.dir("wasmNs").get().asFile
val wasmNativeDir = File(wasmOutputDir, "native")
val wasmShimObject = File(wasmOutputDir, "webrtc_ns_wasm_shim.o")
val wasmModuleJs = File(wasmOutputDir, "webrtc_ns_wasm.js")
val wasmModuleWasm = File(wasmOutputDir, "webrtc_ns_wasm.wasm")

// Emscripten requires Python >= 3.10; the emsdk ships its own interpreter,
// which may not be on PATH (e.g. macOS ships Python 3.9 as /usr/bin/python3).
fun wasmEnv(): Map<String, String> {
    val pythonDir = File(wasmEmsdk, "python").listFiles()?.firstOrNull { it.isDirectory }
    return if (pythonDir != null) {
        mapOf("PATH" to "${pythonDir.absolutePath}/bin:${System.getenv("PATH")}")
    } else {
        emptyMap()
    }
}

val wasmExports = listOf(
    "webrtc_ns_wasm_create",
    "webrtc_ns_wasm_destroy",
    "webrtc_ns_wasm_analyze",
    "webrtc_ns_wasm_process",
    "webrtc_ns_wasm_set_capture_output_usage",
    "webrtc_ns_wasm_sample_rate_hz",
    "webrtc_ns_wasm_num_channels",
    "webrtc_ns_wasm_num_frames_per_channel",
    "webrtc_ns_wasm_version",
    "malloc",
    "free",
).joinToString(",") { "_$it" }

val configureWasmNs by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures the WebRTC NS static library for wasmJs."
    onlyIf { wasmEmcc.isFile }
    doFirst {
        File(wasmOutputDir, "cmake").mkdirs()
        wasmNativeDir.mkdirs()
    }
    workingDir = File(wasmOutputDir, "cmake")
    environment(wasmEnv())
    // A toolchain file disables the generator's normal make discovery, so the
    // make program must be passed explicitly (emcmake does the same).
    val makeProgram = if (hostOs.isWindows) "nmake" else "/usr/bin/make"
    commandLine(
        cmakeExecutable, wasmSrcDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_TOOLCHAIN_FILE=${wasmToolchain.absolutePath}",
        "-DCMAKE_MAKE_PROGRAM=$makeProgram",
        "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${wasmNativeDir.absolutePath}",
    )
}

val buildWasmNs by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libwebrtc_ns.a for wasmJs."
    onlyIf { wasmEmcc.isFile }
    dependsOn(configureWasmNs)
    workingDir = File(wasmOutputDir, "cmake")
    environment(wasmEnv())
    commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    outputs.file(File(wasmNativeDir, "libwebrtc_ns.a"))
}

val compileWasmShim by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the handle-based NS wasm shim."
    onlyIf { wasmEmcc.isFile }
    doFirst { wasmOutputDir.mkdirs() }
    environment(wasmEnv())
    commandLine(
        wasmEmcc.absolutePath, "-O2", "-c",
        File(wasmSrcDir, "webrtc_ns_wasm_shim.c").absolutePath,
        "-I${rootProject.projectDir.resolve("jni/c_api").absolutePath}",
        "-o", wasmShimObject.absolutePath,
    )
    inputs.file(File(wasmSrcDir, "webrtc_ns_wasm_shim.c"))
    inputs.file(rootProject.file("jni/c_api/webrtc_ns_c.h"))
    outputs.file(wasmShimObject)
}

val linkWasmNs by tasks.registering(Exec::class) {
    group = "build"
    description = "Links the WebRTC NS Emscripten module (webrtc_ns_wasm.js/.wasm)."
    onlyIf { wasmEmcc.isFile }
    dependsOn(buildWasmNs, compileWasmShim)
    environment(wasmEnv())
    commandLine(
        wasmEmxx.absolutePath,
        wasmShimObject.absolutePath,
        File(wasmNativeDir, "libwebrtc_ns.a").absolutePath,
        "-o", wasmModuleJs.absolutePath,
        "-O2",
        "-sMODULARIZE=1", "-sEXPORT_NAME=WebrtcNsModule", "-sEXPORT_ES6=1",
        "-sEXPORTED_FUNCTIONS=$wasmExports",
        "-sEXPORTED_RUNTIME_METHODS=ccall,cwrap,UTF8ToString,HEAPF32",
        "-sALLOW_MEMORY_GROWTH=1", "-sINITIAL_MEMORY=16777216",
        "-sENVIRONMENT=web,worker,node", "-sFILESYSTEM=0", "--no-entry",
    )
    inputs.files(wasmShimObject, File(wasmNativeDir, "libwebrtc_ns.a"))
    inputs.file(File(wasmSrcDir, "CMakeLists.txt"))
    outputs.files(wasmModuleJs, wasmModuleWasm)
}

// ==================== wasm: npm package ====================
// The Emscripten module and the JS glue ship as an npm package
// (@enaium/webrtc-ns-kmp): web apps depend on it like any other npm package
// and `import { initWebrtcNsKmp } from "@enaium/webrtc-ns-kmp"` before
// running the Kotlin/Wasm module, so no asset extraction step is involved.
//
// The package version is the project version, so Maven and npm share one
// version.

val npmPackageVersion: String by lazy { rootProject.version.toString() }

val wasmNpmDir = layout.buildDirectory.dir("npm").get().asFile
val wasmGlue = wasmSrcDir.resolve("webrtc_ns_kmp_glue.js")

tasks.register("assembleWasmNpm") {
    group = "build"
    description = "Stages the @enaium/webrtc-ns-kmp npm package in build/npm."
    dependsOn(linkWasmNs)
    inputs.file(wasmGlue)
    inputs.property("npmPackageVersion", npmPackageVersion)
    inputs.files(wasmModuleJs, wasmModuleWasm)
    outputs.dir(wasmNpmDir)
    doFirst {
        if (!wasmModuleJs.isFile || !wasmModuleWasm.isFile) {
            throw GradleException(
                "The wasmJS module was not built: set EMSDK or pass -Pwasm.emsdk=<path>, " +
                    "then run :ns:linkWasmNs.",
            )
        }
        wasmNpmDir.deleteRecursively()
        wasmNpmDir.mkdirs()
        wasmGlue.copyTo(wasmNpmDir.resolve("webrtc_ns_kmp_glue.js"), overwrite = true)
        wasmModuleJs.copyTo(wasmNpmDir.resolve("webrtc_ns_wasm.js"), overwrite = true)
        wasmModuleWasm.copyTo(wasmNpmDir.resolve("webrtc_ns_wasm.wasm"), overwrite = true)
        rootProject.file("LICENSE").copyTo(wasmNpmDir.resolve("LICENSE"), overwrite = true)
        // The wasm binary contains the BSD-3-Clause WebRTC NS sources.
        rootProject.file("webrtc-ns/LICENSE")
            .copyTo(wasmNpmDir.resolve("LICENSE-BSD-3"), overwrite = true)
        wasmNpmDir.resolve("package.json").writeText(
            """
            {
              "name": "@enaium/webrtc-ns-kmp",
              "version": "$npmPackageVersion",
              "description": "Emscripten-compiled WebRTC noise suppressor and JS glue for the webrtc-ns-kmp wasmJs target",
              "license": "MIT",
              "type": "module",
              "exports": {
                ".": "./webrtc_ns_kmp_glue.js",
                "./webrtc_ns_wasm.js": "./webrtc_ns_wasm.js",
                "./package.json": "./package.json"
              },
              "files": ["webrtc_ns_kmp_glue.js", "webrtc_ns_wasm.js", "webrtc_ns_wasm.wasm", "LICENSE", "LICENSE-BSD-3"],
              "homepage": "https://github.com/Enaium/webrtc-ns-kmp",
              "repository": {
                "type": "git",
                "url": "git+https://github.com/Enaium/webrtc-ns-kmp.git"
              }
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.register<Exec>("publishWasmNpm") {
    group = "publishing"
    description = "Publishes @enaium/webrtc-ns-kmp to npm (token: npm.publish.key)."
    dependsOn("assembleWasmNpm")
    workingDir = wasmNpmDir
    // The token lives in the user-level Gradle properties file
    // (~/.gradle/gradle.properties, next to mavenCentralUsername and the
    // signing.* entries); CI writes the same key there from a repository
    // secret. `-Pnpm.publish.key=<token>` overrides it for one-off runs.
    val npmKey = providers.gradleProperty("npm.publish.key")
    doFirst {
        val key = npmKey.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "npm.publish.key is not set: add it to ~/.gradle/gradle.properties " +
                    "or pass -Pnpm.publish.key=<token>",
            )
        environment("NODE_AUTH_TOKEN", key)
        wasmNpmDir.resolve(".npmrc").writeText(
            "//registry.npmjs.org/:_authToken=\${NODE_AUTH_TOKEN}\n",
        )
    }
    commandLine("npm", "publish", "--access", "public", "--tag", "latest")
}

// Headless check of the whole pipeline: Emscripten module -> JS glue ->
// suppressor, driven by Node (see ns/wasm/webrtc_ns_wasm_test.mjs).
tasks.register<Exec>("testWasmNs") {
    group = "verification"
    description = "Runs the Node smoke test against the staged wasmJs npm package."
    dependsOn("assembleWasmNpm")
    workingDir = projectDir
    commandLine("node", wasmSrcDir.resolve("webrtc_ns_wasm_test.mjs").absolutePath)
}

// ==================== Publishing ====================
// Version is read from VERSION_NAME in gradle.properties (or -Pversion).
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "webrtc-ns-kmp",
        // null -> the plugin falls back to project.version (VERSION_NAME in
        // gradle.properties or -Pversion on the command line)
        version = null,
    )

    pom {
        name.set("webrtc-ns-kmp")
        description.set(
            "Kotlin Multiplatform bindings for the WebRTC noise suppressor.",
        )
        url.set("https://github.com/Enaium/webrtc-ns-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("BSD-3-Clause")
                url.set("https://opensource.org/licenses/BSD-3-Clause")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/webrtc-ns-kmp")
            connection.set("scm:git:git@github.com:Enaium/webrtc-ns-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/webrtc-ns-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/webrtc-ns-kmp/issues")
        }
    }
}
