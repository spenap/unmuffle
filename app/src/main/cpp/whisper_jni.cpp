// JNI for WhisperLib: one Session per loaded model, holding whisper's
// context and what the callbacks report while it transcribes.
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

struct Session {
    whisper_context * ctx;
    std::atomic<int> progress{0};
    std::mutex segments_mutex;
    std::string new_segments;  // decoded, not yet taken by Kotlin
};

static Session * session(jlong handle) { return reinterpret_cast<Session *>(handle); }

static void on_progress(whisper_context *, whisper_state *, int progress, void * user_data) {
    static_cast<Session *>(user_data)->progress.store(progress, std::memory_order_relaxed);
}

// Appends each new segment as its text tokens: UTF-8 text, 0x1F, probability,
// 0x1E; then 0x1D. Special tokens (timestamps, end of text) are left out.
static void on_new_segment(whisper_context * ctx, whisper_state * state, int n_new, void * user_data) {
    auto * s = static_cast<Session *>(user_data);
    const int n = whisper_full_n_segments_from_state(state);
    const whisper_token eot = whisper_token_eot(ctx);
    std::lock_guard<std::mutex> lock(s->segments_mutex);
    for (int i = n - n_new; i < n; i++) {
        for (int j = 0; j < whisper_full_n_tokens_from_state(state, i); j++) {
            if (whisper_full_get_token_id_from_state(state, i, j) >= eot) continue;
            char p[16];
            snprintf(p, sizeof p, "%.3f", whisper_full_get_token_p_from_state(state, i, j));
            s->new_segments += whisper_full_get_token_text_from_state(ctx, state, i, j);
            s->new_segments += '\x1f';
            s->new_segments += p;
            s->new_segments += '\x1e';
        }
        s->new_segments += '\x1d';
    }
}

// whisper's and ggml's logs to logcat: the model, the backend, the timings.
// Not debug logs, which can hold tokens.
static void on_log(ggml_log_level level, const char * text, void *) {
    if (level < GGML_LOG_LEVEL_INFO || level == GGML_LOG_LEVEL_CONT) return;
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                       : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_print(priority, "whisper", "%s", text);
}

extern "C" {

// A session for the model at modelPath; 0 if it doesn't load
JNIEXPORT jlong JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_initContext(JNIEnv * env, jobject, jstring modelPath) {
    // ggml's OpenCL backend sets up its device on first use, but whisper
    // asks for the buffer alignment before that: set it up first.
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            ggml_backend_dev_supports_buft(dev, ggml_backend_dev_buffer_type(dev));
        }
    }
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context * ctx = whisper_init_from_file_with_params(path, whisper_context_default_params());
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("Model didn't load");
        return 0;
    }
    auto * s = new Session();
    s->ctx = ctx;
    return reinterpret_cast<jlong>(s);
}

// Transcribes the audio; blocks until done
JNIEXPORT void JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv * env, jobject, jlong handle, jint threads, jfloatArray audio, jstring language, jint audioCtx) {
    Session * s = session(handle);
    const char * lang = env->GetStringUTFChars(language, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = true;     // each message is independent
    params.language = lang;       // a code, or "auto" to detect it
    params.audio_ctx = audioCtx;  // encoder window in frames; 0 for the full 30 s
    params.progress_callback = on_progress;
    params.progress_callback_user_data = s;
    params.new_segment_callback = on_new_segment;
    params.new_segment_callback_user_data = s;
    s->progress.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(s->segments_mutex);
        s->new_segments.clear();
    }
    jfloat * samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize n = env->GetArrayLength(audio);
    LOGI("Transcribing %d samples, %d threads, lang=%s, audio_ctx=%d", (int)n, (int)threads, lang, (int)audioCtx);
    whisper_reset_timings(s->ctx);
    const int ret = whisper_full(s->ctx, params, samples, n);
    whisper_print_timings(s->ctx);  // mel, encoder, decoder: logcat tag "whisper"
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);  // whisper reads it until whisper_full returns
    if (ret != 0) LOGE("whisper_full failed: %d", ret);
}

JNIEXPORT jint JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getProgress(JNIEnv *, jobject, jlong handle) {
    return session(handle)->progress.load(std::memory_order_relaxed);
}

// Segments decoded since the last call, as on_new_segment wrote them. Bytes,
// not a jstring: tokens can split UTF-8 characters, and NewStringUTF expects
// modified UTF-8 and aborts on emoji.
JNIEXPORT jbyteArray JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_takeNewSegments(JNIEnv * env, jobject, jlong handle) {
    Session * s = session(handle);
    std::string taken;
    {
        std::lock_guard<std::mutex> lock(s->segments_mutex);
        taken.swap(s->new_segments);
    }
    jbyteArray bytes = env->NewByteArray((jsize)taken.size());
    env->SetByteArrayRegion(bytes, 0, (jsize)taken.size(), (const jbyte *)taken.data());
    return bytes;
}

// The last transcription's language: the one given, or the one detected
JNIEXPORT jstring JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getLanguage(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(whisper_lang_str(whisper_full_lang_id(session(handle)->ctx)));
}

JNIEXPORT void JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_freeContext(JNIEnv *, jobject, jlong handle) {
    Session * s = session(handle);
    whisper_free(s->ctx);
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jobjectArray JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_getLanguages(JNIEnv * env, jobject) {
    const int n = whisper_lang_max_id() + 1;
    jobjectArray codes = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < n; i++) env->SetObjectArrayElement(codes, i, env->NewStringUTF(whisper_lang_str(i)));
    return codes;
}

// ggml's CPU backends sit next to this library, one per ARM level; load the
// best one this phone supports before whisper asks for a CPU device.
JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    whisper_log_set(on_log, nullptr);
    Dl_info info;
    if (dladdr(reinterpret_cast<void *>(&JNI_OnLoad), &info) && info.dli_fname) {
        std::string dir(info.dli_fname);
        ggml_backend_load_all_from_path(dir.substr(0, dir.rfind('/')).c_str());
    }
    return JNI_VERSION_1_6;
}

} // extern "C"
