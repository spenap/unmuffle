cmake_minimum_required(VERSION 3.22)
project(unmuffle)
set(CMAKE_CXX_STANDARD 17)

# No repo or NDK paths in the libraries, for reproducible builds
get_filename_component(REPO_DIR "${CMAKE_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${REPO_DIR}=." "-ffile-prefix-map=${ANDROID_NDK}=/ndk")

# whisper.cpp with ggml's CPU backend built once per ARM level; at run time
# ggml loads the best one this phone supports (WhisperLib loads them).
set(BUILD_SHARED_LIBS ON)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Spike: a GPU backend next to the CPU ones (gradle -Pgpu=opencl).
# ggml loads it like the CPU ones; without a driver it isn't registered and
# whisper runs on the CPU.
set(UNMUFFLE_GPU "off" CACHE STRING "GPU backend: off, opencl or opencl-generic")
include(FetchContent)
if (UNMUFFLE_GPU MATCHES "^opencl")
    # opencl-generic: without the Adreno kernels, which need OpenCL
    # extensions older Adrenos (6xx) lack
    if (UNMUFFLE_GPU STREQUAL "opencl-generic")
        set(GGML_OPENCL_USE_ADRENO_KERNELS OFF CACHE BOOL "" FORCE)
    endif()
    FetchContent_Declare(opencl_headers
        URL https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/v2025.07.22.tar.gz
        URL_HASH SHA256=98f0a3ea26b4aec051e533cb1750db2998ab8e82eda97269ed6efe66ec94a240)
    FetchContent_Populate(opencl_headers)
    add_library(unmuffle_opencl SHARED opencl_loader.c)
    target_link_libraries(unmuffle_opencl dl log)
    # FindOpenCL takes these as found; ggml-opencl links the loader above.
    set(OpenCL_INCLUDE_DIR ${opencl_headers_SOURCE_DIR} CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY unmuffle_opencl CACHE STRING "" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
find_library(LOG_LIB log)
target_link_libraries(whisper_jni whisper ggml ${LOG_LIB})
