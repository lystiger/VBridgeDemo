cmake_minimum_required(VERSION 3.22.1)

project(whisper_native)

set(CMAKE_CXX_STANDARD 17)

# Paths to vendored sources
set(WHISPER_VENDOR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor)

set(WHISPER_SRC
    ${WHISPER_VENDOR_DIR}/src/whisper.cpp
)

set(GGML_SRC
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml.c
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-backend-meta.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-backend-dl.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-opt.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/gguf.cpp
)

set(GGML_CPU_SRC
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/quants.c
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/repack.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/traits.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/vec.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/ops.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/hbm.cpp

    # ARM specific
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/arch/arm/repack.cpp
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp
)

add_library(whisper SHARED
    jni.c
    ${WHISPER_SRC}
    ${GGML_SRC}
    ${GGML_CPU_SRC}
)

target_include_directories(whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WHISPER_VENDOR_DIR}
    ${WHISPER_VENDOR_DIR}/src
    ${WHISPER_VENDOR_DIR}/include
    ${WHISPER_VENDOR_DIR}/ggml/include
    ${WHISPER_VENDOR_DIR}/ggml/src
    ${WHISPER_VENDOR_DIR}/ggml/src/ggml-cpu
)

target_link_libraries(whisper
    android
    log
    dl
)

target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION=\"1.13.3\")
target_compile_definitions(whisper PRIVATE GGML_VERSION=\"unknown\")
target_compile_definitions(whisper PRIVATE GGML_COMMIT=\"unknown\")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(whisper PUBLIC GGML_USE_CPU_AARCH64)
endif()
