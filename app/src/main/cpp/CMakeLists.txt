cmake_minimum_required(VERSION 3.22)
project(whisper_transchat)
set(CMAKE_CXX_STANDARD 17)

# whisper.cpp submodule is at the same level as this CMakeLists.txt
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(SOURCE_FILES
    ${WHISPER_CPP_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp
)

find_library(LOG_LIB log)
include(FetchContent)

function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    # whisper.cpp v1.8.3 defines WHISPER_VERSION via its own CMakeLists.txt; since we
    # compile src/whisper.cpp directly we must supply it as a compile definition.
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="1.8.3")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE
            -O3
            -fvisibility=hidden
            -fvisibility-inlines-hidden
            -ffunction-sections
            -fdata-sections
        )
        target_link_options(${target_name} PRIVATE
            -Wl,--gc-sections
            -Wl,--exclude-libs,ALL
            -flto
        )
    endif ()

    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_CPP_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)

    target_include_directories(${target_name} PRIVATE
        ${WHISPER_CPP_DIR}
        ${WHISPER_CPP_DIR}/src
        ${WHISPER_CPP_DIR}/include
        ${WHISPER_CPP_DIR}/ggml/include
        ${WHISPER_CPP_DIR}/ggml/src
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu  # required since PR #2624 (Dec 2024)
    )
endfunction()

# arm64-v8a optimized variant (FP16 acceleration) — primary build target
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
endif ()

# Baseline fallback target (always built)
build_library("whisper")
