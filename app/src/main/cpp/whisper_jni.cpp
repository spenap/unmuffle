#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.cpp/include/whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// Initialize from absolute file path — always use this, never byte array (OOM on 190MB)
JNIEXPORT jlong JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject /* thiz */, jstring modelPathStr) {
    const char *modelPath = env->GetStringUTFChars(modelPathStr, nullptr);
    LOGI("Loading model from: %s", modelPath);
    struct whisper_context_params params = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(modelPath, params);
    env->ReleaseStringUTFChars(modelPathStr, modelPath);
    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from file");
        return 0L;
    }
    LOGI("Whisper context initialized successfully");
    return (jlong)(intptr_t)ctx;
}

// Run transcription — blocking, call from background thread only (15-60s on mid-range)
JNIEXPORT void JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr, jint numThreads, jfloatArray audioData) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    jfloat *audioDataArr = env->GetFloatArrayElements(audioData, nullptr);
    jsize audioDataLength = env->GetArrayLength(audioData);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = numThreads;
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_context       = true;          // each clip is independent
    params.single_segment   = false;         // let whisper segment naturally via timestamps
    params.language         = "auto";        // auto-detect (multilingual model)

    LOGI("Starting transcription with %d threads on %d samples", numThreads, (int)audioDataLength);
    int ret = whisper_full(ctx, params, audioDataArr, (int)audioDataLength);
    env->ReleaseFloatArrayElements(audioData, audioDataArr, JNI_ABORT);
    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
    }
    LOGI("Transcription complete, ret=%d segments=%d", ret, whisper_full_n_segments(ctx));
}

JNIEXPORT jint JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    return whisper_full_n_segments(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr, jint index) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    const char *text = whisper_full_get_segment_text(ctx, index);
    return env->NewStringUTF(text);
}

JNIEXPORT void JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    LOGI("Freeing whisper context");
    whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject /* thiz */) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
