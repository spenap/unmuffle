#include <jni.h>
#include <string>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <android/log.h>
#include <dlfcn.h>
#include "whisper.cpp/include/whisper.h"
#include "whisper.cpp/ggml/include/ggml-backend.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct ProgressState {
    std::atomic<int> progress{0};
    // Segments decoded but not yet taken by Kotlin (see whisper_new_segment_cb).
    std::mutex segments_mutex;
    std::string new_segments;
};

// File-scoped — safe because WhisperContext serializes all access
// through its single-thread executor. If multiple contexts are ever
// needed, allocate per-context and pass via user_data.
static ProgressState g_progress_state;

static void whisper_progress_cb(
    struct whisper_context* /*ctx*/,
    struct whisper_state*   /*state*/,
    int progress,
    void* user_data)
{
    auto* ps = static_cast<ProgressState*>(user_data);
    ps->progress.store(progress, std::memory_order_relaxed);
}

// Appends each new segment as its text tokens: UTF-8 text, 0x1F, probability,
// 0x1E; then 0x1D. Special tokens (timestamps, end of text) are left out.
static void whisper_new_segment_cb(
    struct whisper_context* ctx,
    struct whisper_state*   state,
    int n_new,
    void* user_data)
{
    auto* ps = static_cast<ProgressState*>(user_data);
    const int n = whisper_full_n_segments_from_state(state);
    const whisper_token eot = whisper_token_eot(ctx);
    std::lock_guard<std::mutex> lock(ps->segments_mutex);
    for (int i = n - n_new; i < n; i++) {
        const int n_tokens = whisper_full_n_tokens_from_state(state, i);
        for (int j = 0; j < n_tokens; j++) {
            if (whisper_full_get_token_id_from_state(state, i, j) >= eot) continue;
            char p[16];
            snprintf(p, sizeof p, "%.3f", whisper_full_get_token_p_from_state(state, i, j));
            ps->new_segments += whisper_full_get_token_text_from_state(ctx, state, i, j);
            ps->new_segments += '\x1f';
            ps->new_segments += p;
            ps->new_segments += '\x1e';
        }
        ps->new_segments += '\x1d';
    }
}

extern "C" {

// Initialize from absolute file path — always use this, never byte array (OOM on 190MB)
JNIEXPORT jlong JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_initContext(
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
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr, jint numThreads,
        jfloatArray audioData, jstring languageCode) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    jfloat *audioDataArr = env->GetFloatArrayElements(audioData, nullptr);
    jsize audioDataLength = env->GetArrayLength(audioData);

    const char* lang = env->GetStringUTFChars(languageCode, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = numThreads;
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_context       = true;          // each clip is independent
    params.single_segment   = false;         // let whisper segment naturally via timestamps
    params.language         = lang;          // runtime language code (e.g. "auto", "en", "es")

    // Reset and register progress and segment callbacks
    g_progress_state.progress.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_progress_state.segments_mutex);
        g_progress_state.new_segments.clear();
    }
    params.progress_callback              = whisper_progress_cb;
    params.progress_callback_user_data    = &g_progress_state;
    params.new_segment_callback           = whisper_new_segment_cb;
    params.new_segment_callback_user_data = &g_progress_state;

    LOGI("Starting transcription with %d threads on %d samples, lang=%s", numThreads, (int)audioDataLength, lang);
    int ret = whisper_full(ctx, params, audioDataArr, (int)audioDataLength);
    // Release AFTER whisper_full() — params.language pointer must remain valid during inference
    env->ReleaseStringUTFChars(languageCode, lang);
    env->ReleaseFloatArrayElements(audioData, audioDataArr, JNI_ABORT);
    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
    }
    LOGI("Transcription complete, ret=%d segments=%d", ret, whisper_full_n_segments(ctx));
}

JNIEXPORT jint JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    return whisper_full_n_segments(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr, jint index) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    const char *text = whisper_full_get_segment_text(ctx, index);
    return env->NewStringUTF(text);
}

JNIEXPORT void JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject /* thiz */, jlong contextPtr) {
    auto *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    LOGI("Freeing whisper context");
    whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject /* thiz */) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jobjectArray JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getLanguages(
        JNIEnv *env, jobject /* thiz */) {
    int n = whisper_lang_max_id() + 1;
    jobjectArray codes = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < n; i++) {
        env->SetObjectArrayElement(codes, i, env->NewStringUTF(whisper_lang_str(i)));
    }
    return codes;
}

JNIEXPORT jint JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getProgress(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_progress_state.progress.load(std::memory_order_relaxed);
}

// Segments decoded since the last call, as whisper_new_segment_cb wrote them.
// Bytes, not a jstring: tokens can split UTF-8 characters, and NewStringUTF
// expects modified UTF-8 and aborts on emoji.
JNIEXPORT jbyteArray JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_takeNewSegments(
        JNIEnv* env, jobject /*thiz*/) {
    std::string taken;
    {
        std::lock_guard<std::mutex> lock(g_progress_state.segments_mutex);
        taken.swap(g_progress_state.new_segments);
    }
    jbyteArray bytes = env->NewByteArray((jsize)taken.size());
    env->SetByteArrayRegion(bytes, 0, (jsize)taken.size(), (const jbyte*)taken.data());
    return bytes;
}

} // extern "C"

// ggml's CPU backends sit next to this library, one per ARM level; load the
// best one this phone supports before whisper asks for a CPU device.
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    Dl_info info;
    if (dladdr(reinterpret_cast<void*>(&JNI_OnLoad), &info) && info.dli_fname) {
        std::string dir(info.dli_fname);
        ggml_backend_load_all_from_path(dir.substr(0, dir.rfind('/')).c_str());
    }
    return JNI_VERSION_1_6;
}
