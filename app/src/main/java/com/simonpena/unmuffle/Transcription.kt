package com.simonpena.unmuffle

import android.os.SystemClock
import android.util.Log

private const val TAG = "Transcription"

/** What the transcription screen shows. */
sealed class UiState {
    /**
     * [progress] is -1 before inference starts; [partial] is the text so far
     * ([words] words); [placeholders] is the words probably still to come.
     */
    data class Processing(
        val progress: Int = -1,
        val partial: String = "",
        val words: Int = 0,
        val placeholders: Int = 0,
        val unsure: List<IntRange> = emptyList(),
    ) : UiState()
    /**
     * [unsure] are the character ranges of the words whisper is unsure of;
     * [language] the code it transcribed in, [detected] if whisper chose it.
     */
    data class Success(
        val transcript: String,
        val unsure: List<IntRange> = emptyList(),
        val language: String = Languages.AUTO,
        val detected: Boolean = false,
    ) : UiState()
    data class NoSpeech(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

/** What [Transcription] needs from whisper; [Whisper] in the app, a fake in tests. */
interface Recognizer {
    /** See [Whisper.transcribe]. */
    suspend fun transcribe(
        pcm: FloatArray,
        language: String,
        audioCtx: Int = 0,
        onProgress: (Int) -> Unit = {},
        onSegment: (List<Word>) -> Unit = {},
    )

    /** The last transcription's language: the one given, or the one detected. */
    suspend fun language(): String
}

/**
 * Transcribes a message and reports what the screen shows as it goes. With
 * a language set, a preview of the first seconds ([FirstChunk]) comes
 * first; the whole message then replaces it.
 */
class Transcription(
    private val recognizer: Recognizer,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    suspend fun run(pcm: FloatArray, language: String, show: (UiState) -> Unit) {
        val streaming = StreamingTranscript(pcm.size / 16000f, clock)
        show(streaming.state)
        val startMs = clock()
        fun log(what: String) = Log.i(TAG, "$what after ${clock() - startMs} ms")
        var shown = false
        val onSegment = { words: List<Word> ->
            show(streaming.segment(words))
            if (!shown && streaming.state.words > 0) {
                shown = true
                log("First words")
            }
        }
        val cut = FirstChunk.cut(pcm, language)
        if (cut != null) {
            recognizer.transcribe(pcm.copyOfRange(0, cut), language, FirstChunk.audioCtx(cut), onSegment = onSegment)
            log("Preview")
        }
        var replacing = cut != null
        recognizer.transcribe(
            pcm, language,
            onProgress = { show(streaming.progress(it)) },
            onSegment = { words ->
                if (replacing) {
                    streaming.startOver()
                    replacing = false
                    log("Preview replaced")
                }
                onSegment(words)
            },
        )
        if (replacing) streaming.startOver() // the whole message had no segments
        Log.i(TAG, "Transcribed in ${clock() - startMs} ms")

        val text = streaming.state
        if (!HallucinationFilter.isSpeech(text.partial)) {
            show(UiState.NoSpeech("No speech detected"))
            return
        }
        val used = recognizer.language()
        Log.i(TAG, StreamingTranscript.summary(text.words, streaming.elapsedSeconds(), text.unsure.size) + " · $used")
        show(UiState.Success(text.partial, text.unsure, used, detected = language == Languages.AUTO))
    }
}
