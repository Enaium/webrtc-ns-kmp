# Copyright (c) 2026 Enaium
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

# Builds WebRTC NS and the C API as a static library for Emscripten (wasmJs).
# The webrtc_ns_wasm_shim.c wrapper is compiled and linked together with
# libwebrtc_ns.a by the Gradle linkWasmNs task (emcc), producing the
# webrtc_ns_wasm.js module that Kotlin/Wasm drives through its JS glue.
cmake_minimum_required(VERSION 3.21)
project(webrtc-ns-kmp-wasm C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED WEBRTC_NS_DIR)
    set(WEBRTC_NS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../webrtc-ns")
endif()
if(NOT DEFINED C_API_DIR)
    set(C_API_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../jni/c_api")
endif()

file(GLOB_RECURSE NS_SRC
    ${WEBRTC_NS_DIR}/src/**/*.cc
    ${WEBRTC_NS_DIR}/src/**/*.c
)

# wasm32 has neither the x86 SIMD paths nor a Linux/Android sysfs or cpufeatures
# implementation; the generic CPU probe in cpu_features.cc is what remains.
list(FILTER NS_SRC EXCLUDE REGEX ".*(_avx2|_sse2|_sse|_neon)\\.cc$")
list(FILTER NS_SRC EXCLUDE REGEX ".*cpu_features_(android|linux)\\.c$")

add_library(webrtc_ns STATIC ${NS_SRC}
    ${C_API_DIR}/webrtc_ns_c.cc
    ${C_API_DIR}/webrtc_ns_c.h
)

# The extracted sources include WebRTC's POSIX branches; defining WEBRTC_LINUX
# would select Linux-only headers (sys/prctl.h) that Emscripten does not ship.
target_compile_definitions(webrtc_ns PRIVATE WEBRTC_POSIX)

if(MSVC)
    target_compile_options(webrtc_ns PRIVATE /W3 /EHsc)
else()
    target_compile_options(webrtc_ns PRIVATE -Wall -Wno-deprecated -fexceptions)
endif()

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
