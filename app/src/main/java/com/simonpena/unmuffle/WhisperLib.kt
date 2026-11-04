package com.simonpena.unmuffle

/**
 * The JNI functions in whisper_jni.cpp; use [Whisper]. The names (package,
 * class, companion) are part of the native symbols: renaming breaks them
 * without a compile error. Loading the library also loads ggml's CPU backend
 * for this phone.
 */
internal class WhisperLib {
    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }

        /** A session for the model at [modelPath], or 0 if it doesn't load. */
        external fun initContext(modelPath: String): Long

        /** Blocks until done. [audioCtx]: the encoder window in frames, 0 for the full 30 s. */
        external fun fullTranscribe(handle: Long, threads: Int, audio: FloatArray, language: String, audioCtx: Int)

        /** 0–100, while [fullTranscribe] runs. */
        external fun getProgress(handle: Long): Int

        /** The segments decoded since the last call, as [TokenWords] parses them. */
        external fun takeNewSegments(handle: Long): ByteArray

        /** The last transcription's language: the one given, or the one detected. */
        external fun getLanguage(handle: Long): String

        external fun freeContext(handle: Long)
        external fun getSystemInfo(): String
        external fun getLanguages(): Array<String>
    }
}
