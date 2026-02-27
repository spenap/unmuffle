package com.simonpena.transchat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "WhisperEngine"

/**
 * Public API for Whisper inference. Used by TranscriptionViewModel (Phase 3) and
 * the smoke test Activity (Plan 04).
 *
 * Threading contract:
 *   - loadModel() must be called from a coroutine (it does IO and JNI work)
 *   - transcribe() runs on Dispatchers.Default — never on Main (ANR after 5s)
 *   - release() frees native memory; call when Activity/ViewModel is destroyed
 *
 * Lifecycle: one engine per app session. The 190MB model stays resident in memory
 * once loaded — reloading on every transcription call would take 2-5s per call.
 */
class WhisperEngine {

    private var whisperContext: WhisperContext? = null

    /** True if the model has been loaded and the native context is ready. */
    val isModelLoaded: Boolean
        get() = whisperContext != null

    /**
     * Load the Whisper model from [absoluteFilePath].
     *
     * @param absoluteFilePath Must be an absolute path inside filesDir — e.g.
     *   `context.filesDir.absolutePath + "/models/ggml-small-q5_1.bin"`.
     *   Never pass a relative path; JNI will fail silently.
     * @return true on success, false if file missing or model load failed (OOM, corrupt)
     */
    suspend fun loadModel(absoluteFilePath: String): Boolean = withContext(Dispatchers.Default) {
        // Release any previously loaded context first
        whisperContext?.release()
        whisperContext = null

        Log.i(TAG, "Loading model: $absoluteFilePath")
        val ctx = try {
            WhisperContext.createContextFromFile(absoluteFilePath)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during model load: ${e.message}", e)
            null
        }

        if (ctx == null) {
            Log.e(TAG, "Model load returned null — file missing, corrupt, or OOM")
            return@withContext false
        }

        whisperContext = ctx
        Log.i(TAG, "Model loaded successfully. System info: ${WhisperContext.getSystemInfo()}")
        true
    }

    /**
     * Transcribe [audioData] (16kHz mono float32 PCM) and return the transcript string.
     *
     * @param onProgress Called with 0-100 progress values during inference. Default no-op.
     * @throws IllegalStateException if [loadModel] has not been called successfully
     * @return Transcript text, or empty string if no speech detected (no_speech_thold applied)
     */
    suspend fun transcribe(audioData: FloatArray, onProgress: (Int) -> Unit = {}): String {
        val ctx = whisperContext
            ?: throw IllegalStateException("Model not loaded — call loadModel() first")

        val startMs = System.currentTimeMillis()
        Log.i(TAG, "Starting transcription of ${audioData.size} samples (${audioData.size / 16000.0f}s)")

        return withContext(Dispatchers.Default) {
            val result = ctx.transcribeData(audioData, onProgress)
            val durationMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "Transcription complete in ${durationMs}ms, result: '${result.take(80)}'")
            result
        }
    }

    /**
     * Release native Whisper context and free ~190MB of native memory.
     * Call from ViewModel.onCleared() or Application.onTerminate().
     */
    suspend fun release() {
        whisperContext?.release()
        whisperContext = null
        Log.i(TAG, "WhisperEngine released")
    }
}
