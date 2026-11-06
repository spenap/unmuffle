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

# Spike: a GPU backend next to the CPU ones (gradle -Pgpu=opencl|vulkan).
# ggml loads it like the CPU ones; without a driver it isn't registered and
# whisper runs on the CPU.
set(UNMUFFLE_GPU "off" CACHE STRING "GPU backend: off, opencl, opencl-generic or vulkan")
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
elseif (UNMUFFLE_GPU STREQUAL "vulkan")
    # The NDK has Vulkan's C headers and loader, not the C++ or SPIR-V headers.
    FetchContent_Declare(vulkan_headers
        URL https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.4.341.tar.gz
        URL_HASH SHA256=8876aad926b2e72bdefddc34885472d5332e2d20aa3ed57313466f0bc982cf3f)
    FetchContent_Declare(spirv_headers
        URL https://github.com/KhronosGroup/SPIRV-Headers/archive/refs/tags/vulkan-sdk-1.4.341.0.tar.gz
        URL_HASH SHA256=cab0a654c4917e16367483296b44cdb1d614e3120c721beafcd37e3a8580486c)
    FetchContent_Populate(vulkan_headers)
    FetchContent_Populate(spirv_headers)
    include_directories(BEFORE ${vulkan_headers_SOURCE_DIR}/include ${spirv_headers_SOURCE_DIR}/include)
    set(Vulkan_INCLUDE_DIR ${vulkan_headers_SOURCE_DIR}/include CACHE PATH "" FORCE)
    # ggml calls Vulkan 1.1 functions directly, which libvulkan exports from
    # Android 9 (API 28); on older phones the backend doesn't load.
    set(Vulkan_LIBRARY ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28/libvulkan.so CACHE FILEPATH "" FORCE)
    # The NDK's glslc (2022.3) lacks the integer dot product and cooperative
    # matrix extensions ggml uses on newer GPUs (Adreno 7xx/8xx): a newer
    # glslc on PATH comes first.
    find_program(UNMUFFLE_GLSLC glslc PATHS ${ANDROID_NDK}/shader-tools/linux-x86_64 NO_DEFAULT_PATH)
    set(Vulkan_GLSLC_EXECUTABLE ${UNMUFFLE_GLSLC} CACHE FILEPATH "" FORCE)
    message(STATUS "glslc: ${UNMUFFLE_GLSLC}")
    # ggml only checks SPIRV-Headers is there; the include path above serves it.
    file(WRITE ${CMAKE_BINARY_DIR}/spirv/SPIRV-HeadersConfig.cmake "")
    set(SPIRV-Headers_DIR ${CMAKE_BINARY_DIR}/spirv CACHE PATH "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
find_library(LOG_LIB log)
target_link_libraries(whisper_jni whisper ggml ${LOG_LIB})
# Spike: gradle -PflashAttn=off turns whisper's flash attention off
if (UNMUFFLE_FLASH_ATTN STREQUAL "off")
    target_compile_definitions(whisper_jni PRIVATE UNMUFFLE_NO_FLASH_ATTN)
endif()
