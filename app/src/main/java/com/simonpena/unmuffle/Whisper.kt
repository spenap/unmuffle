package com.simonpena.unmuffle

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "Whisper"

/**
 * A loaded whisper model. Native calls run one at a time on its own thread:
 * whisper.cpp isn't thread-safe.
 */
class Whisper private constructor(private val handle: Long) {
    private val executor = Executors.newSingleThreadExecutor()
    private val thread = executor.asCoroutineDispatcher()
    private var poller: Job? = null

    /**
     * Transcribes [pcm] (16 kHz mono) in [language], or "auto" to detect it,
     * with an encoder window of [audioCtx] frames (0 for the full 30 s).
     * Reports progress (0–100) and each segment's words as they are decoded,
     * from one thread at a time.
     */
    suspend fun transcribe(
        pcm: FloatArray,
        language: String,
        audioCtx: Int = 0,
        onProgress: (Int) -> Unit = {},
        onSegment: (List<Word>) -> Unit = {},
    ) = withContext(thread) {
        fun takeSegments() = TokenWords.parse(WhisperLib.takeNewSegments(handle)).forEach(onSegment)
        val startMs = SystemClock.elapsedRealtime()
        // The native call blocks this thread; another one reports while it runs.
        val poller = launch(Dispatchers.Default) {
            while (isActive) {
                onProgress(WhisperLib.getProgress(handle))
                takeSegments()
                delay(200)
            }
        }
        this@Whisper.poller = poller
        try {
            WhisperLib.fullTranscribe(handle, WhisperCpuConfig.preferredThreadCount, pcm, language, audioCtx)
        } finally {
            poller.cancelAndJoin()
        }
        takeSegments()
        onProgress(100)
        Log.i(TAG, "Transcription complete in ${SystemClock.elapsedRealtime() - startMs}ms")
    }

    /** The last transcription's language: the one given, or the one detected. */
    suspend fun language(): String = withContext(thread) { WhisperLib.getLanguage(handle) }

    /** Frees the model once any transcription running, and its poller, have finished. */
    fun close() {
        executor.execute {
            runBlocking { poller?.cancelAndJoin() }
            WhisperLib.freeContext(handle)
        }
        executor.shutdown()
    }

    companion object {
        /** Loads the model at [path]; null if it doesn't load (missing, corrupt, out of memory). */
        suspend fun load(path: String): Whisper? = withContext(Dispatchers.IO) {
            val handle = WhisperLib.initContext(path)
            if (handle == 0L) null else Whisper(handle).also { Log.i(TAG, "Loaded; ${systemInfo()}") }
        }

        fun systemInfo(): String = WhisperLib.getSystemInfo()

        /** The language codes whisper transcribes, e.g. "en", "es". */
        fun languages(): List<String> = WhisperLib.getLanguages().toList()
    }
}
