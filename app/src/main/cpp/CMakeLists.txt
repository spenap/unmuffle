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
add_subdirectory(whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
find_library(LOG_LIB log)
target_link_libraries(whisper_jni whisper ggml ${LOG_LIB})
