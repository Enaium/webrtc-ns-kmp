cmake_minimum_required(VERSION 3.16)

project(webrtc_ns C CXX)
set(CMAKE_CXX_STANDARD 20)

# Builds the JNI shared library by default. Native (cinterop) targets build
# only the static library by passing -DBUILD_JNI=OFF.
option(BUILD_JNI "Build the JNI shared library" ON)

# ---- webrtc-ns submodule + C API ----
set(WEBRTC_NS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../webrtc-ns)
set(C_API_DIR ${CMAKE_CURRENT_SOURCE_DIR}/c_api)

add_library(webrtc_ns STATIC)

if(MSVC)
    target_compile_options(webrtc_ns PRIVATE /W3 /EHsc /bigobj)
else()
    target_compile_options(webrtc_ns PRIVATE -Wall -fPIC -Wno-deprecated -fexceptions)
endif()

if(ANDROID)
    target_compile_definitions(webrtc_ns PRIVATE WEBRTC_ANDROID WEBRTC_POSIX)
    if(ANDROID_ABI STREQUAL "x86")
        target_compile_options(webrtc_ns PRIVATE -msse2)
    endif()
    target_include_directories(webrtc_ns PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures)
elseif(APPLE)
    target_compile_definitions(webrtc_ns PRIVATE WEBRTC_MAC WEBRTC_POSIX)
elseif(WIN32)
    target_compile_definitions(webrtc_ns PRIVATE WEBRTC_WIN)
    if(MINGW)
        target_compile_options(webrtc_ns PRIVATE -mno-ms-bitfields)
        # MinGW-w64 cross-compilers don't add their sysroot include dir to
        # the default search path; add it explicitly so Windows.h and the
        # mingw-w64 libc headers resolve.
        target_include_directories(webrtc_ns SYSTEM PRIVATE /usr/x86_64-w64-mingw32/include)
    endif()
elseif(UNIX AND NOT APPLE)
    target_compile_definitions(webrtc_ns PRIVATE WEBRTC_LINUX WEBRTC_POSIX)
endif()

file(GLOB_RECURSE NS_SRC
    ${WEBRTC_NS_DIR}/src/**/*.cc
    ${WEBRTC_NS_DIR}/src/**/*.c
    ${WEBRTC_NS_DIR}/src/**/*.cpp
    ${WEBRTC_NS_DIR}/src/**/*.h
)

# On Apple, CMAKE_SYSTEM_PROCESSOR reflects the host even when cross-compiling
# via CMAKE_OSX_ARCHITECTURES, so derive the effective arch from it first.
if(APPLE AND CMAKE_OSX_ARCHITECTURES)
    set(EFFECTIVE_ARCH ${CMAKE_OSX_ARCHITECTURES})
else()
    set(EFFECTIVE_ARCH ${CMAKE_SYSTEM_PROCESSOR})
endif()

message(STATUS "EFFECTIVE_ARCH: ${EFFECTIVE_ARCH}")

if(NOT (EFFECTIVE_ARCH MATCHES "x86_64|AMD64|amd64|i686|x86"))
    # Non-x86: exclude AVX2, SSE2 and SSE files (not available on ARM).
    list(FILTER NS_SRC EXCLUDE REGEX ".*_avx2\\.cc$")
    list(FILTER NS_SRC EXCLUDE REGEX ".*_sse2\\.cc$")
    list(FILTER NS_SRC EXCLUDE REGEX ".*_sse\\.cc$")
endif()

# Exclude NEON files on non-ARM platforms (they include arm_neon.h).
if(NOT (EFFECTIVE_ARCH MATCHES "aarch64|arm64|ARM64"))
    list(FILTER NS_SRC EXCLUDE REGEX ".*_neon\\.cc$")
endif()

if(ANDROID)
    list(FILTER NS_SRC EXCLUDE REGEX "cpu_features_linux\\.c$")
elseif(WIN32)
    list(FILTER NS_SRC EXCLUDE REGEX "cpu_features_android\\.c$")
    list(FILTER NS_SRC EXCLUDE REGEX "cpu_features_linux\\.c$")
elseif(APPLE)
    list(FILTER NS_SRC EXCLUDE REGEX "cpu_features_android\\.c$")
    list(FILTER NS_SRC EXCLUDE REGEX "cpu_features_linux\\.c$")
else()
    list(FILTER NS_SRC EXCLUDE REGEX "cpu_features_android\\.c$")
endif()

target_sources(webrtc_ns PRIVATE ${NS_SRC})
target_sources(webrtc_ns PRIVATE
    ${C_API_DIR}/webrtc_ns_c.cc
    ${C_API_DIR}/webrtc_ns_c.h
)

target_include_directories(webrtc_ns PUBLIC
    ${WEBRTC_NS_DIR}/src/
    ${WEBRTC_NS_DIR}/src/api
    ${WEBRTC_NS_DIR}/src/base
    ${WEBRTC_NS_DIR}/src/base/rtc_base
    ${WEBRTC_NS_DIR}/src/base/system_wrappers/include
    ${WEBRTC_NS_DIR}/src/audio_processing
    ${WEBRTC_NS_DIR}/src/audio_processing/include
    ${WEBRTC_NS_DIR}/src/audio_processing/ns
    ${WEBRTC_NS_DIR}/src/audio_processing/utility
    ${WEBRTC_NS_DIR}/src/audio_processing/resampler
    ${WEBRTC_NS_DIR}/src/common_audio
    ${WEBRTC_NS_DIR}/src/common_audio/third_party/ooura/fft_size_256
    ${WEBRTC_NS_DIR}/src/absl
    ${C_API_DIR}
)

set_target_properties(webrtc_ns PROPERTIES LINKER_LANGUAGE CXX)

# ---- JNI shared library ----
if(BUILD_JNI)
    add_library(webrtc_ns_jni SHARED ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp)
    set_target_properties(webrtc_ns_jni PROPERTIES OUTPUT_NAME webrtc_ns_jni)
    if(WIN32)
        # MinGW prefixes DLLs with "lib"; strip it so the artifact is
        # webrtc_ns_jni.dll everywhere (NativeLoader expects no prefix).
        set_target_properties(webrtc_ns_jni PROPERTIES PREFIX "")
    endif()

    if(ANDROID)
        find_library(log-lib log)
        if(log-lib)
            target_link_libraries(webrtc_ns_jni PRIVATE ${log-lib})
        endif()
        # Align the .so to 16KB pages, required for 16KB page-size devices
        # (Android 15+); see https://developer.android.com/guide/practices/page-sizes.
        target_link_options(webrtc_ns_jni PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384"
        )
    elseif(JNI_INCLUDE_DIR)
        target_include_directories(webrtc_ns_jni PRIVATE ${JNI_INCLUDE_DIR})
        if(JNI_INCLUDE_DIR_PLATFORM)
            target_include_directories(webrtc_ns_jni PRIVATE ${JNI_INCLUDE_DIR_PLATFORM})
        endif()
    else()
        find_package(JNI REQUIRED)
        target_include_directories(webrtc_ns_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()

    target_include_directories(webrtc_ns_jni PRIVATE ${C_API_DIR})
    target_link_libraries(webrtc_ns_jni PRIVATE webrtc_ns)

    if(APPLE)
        target_link_libraries(webrtc_ns_jni PRIVATE "-framework CoreFoundation")
    elseif(WIN32)
        # timeGetTime() used by rtc_base/time_utils.cc
        target_link_libraries(webrtc_ns_jni PRIVATE winmm)
    endif()
endif()
