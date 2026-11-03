package com.simonpena.unmuffle

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The transcription screen's state while whisper.cpp runs: progress, the
 * segments decoded so far (without non-speech markers), how many words are
 * probably still to come, and words transcribed per second. The final
 * transcript still goes through [HallucinationFilter].
 *
 * Words to come start at [audioSeconds] × [SPEECH_WORDS_PER_SECOND], then
 * follow the words per second of audio transcribed so far; progress is the
 * share of audio processed.
 */
class StreamingTranscript(
    private val audioSeconds: Float = 0f,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startMs = clock()
    private val text = StringBuilder()
    private var words = 0
    private val unsure = mutableListOf<IntRange>()

    var state = UiState.Processing(placeholders = remaining(-1))
        private set

    fun progress(percent: Int): UiState.Processing {
        state = state.copy(progress = percent, placeholders = remaining(percent), wordsPerSecond = rate())
        return state
    }

    /** Appends a segment's words, without non-speech markers such as "[BLANK_AUDIO]" or "(music)". */
    fun segment(segment: List<Word>): UiState.Processing {
        var inMarker = false
        val speech = segment.filter { word ->
            if (word.text.startsWith('[') || word.text.startsWith('(')) inMarker = true
            val keep = !inMarker
            if (word.text.endsWith(']') || word.text.endsWith(')')) inMarker = false
            keep
        }
        if (speech.isEmpty()) return state
        for (word in speech) {
            if (text.isNotEmpty()) text.append(' ')
            if (word.p < UNSURE_BELOW) unsure += text.length until text.length + word.text.length
            text.append(word.text)
        }
        words += speech.size
        state = state.copy(
            partial = text.toString(), words = words, unsure = unsure.toList(),
            placeholders = remaining(state.progress), wordsPerSecond = rate(),
        )
        return state
    }

    fun elapsedSeconds(): Float = (clock() - startMs) / 1000f

    private fun rate(): Float? {
        val seconds = elapsedSeconds()
        return if (words == 0 || seconds <= 0f) null else words / seconds
    }

    private fun remaining(percent: Int): Int = when {
        percent >= 100 -> 0
        words > 0 && percent > 0 -> (words * (100 - percent) / percent.toFloat()).roundToInt()
        else -> max(0, (audioSeconds * (100 - max(percent, 0)) / 100 * SPEECH_WORDS_PER_SECOND).roundToInt() - words)
    }

    companion object {
        /** Typical conversational speech in Spanish and English. */
        const val SPEECH_WORDS_PER_SECOND = 2.6f

        /**
         * Words whisper is less sure of than this are marked. At 0.3, two voice
         * messages had 4% of their words marked, including every clearly
         * misheard one.
         */
        const val UNSURE_BELOW = 0.3f

        private val WHITESPACE = Regex("\\s+")

        fun countWords(text: String): Int = text.trim().let { if (it.isEmpty()) 0 else it.split(WHITESPACE).size }

        fun status(state: UiState.Processing): String = when {
            state.progress < 0 -> "Transcribing..."
            state.wordsPerSecond == null ->
                if (state.placeholders > 0) "Transcribing... about ${state.placeholders} words" else "Transcribing..."
            else -> "Transcribing... ${state.words} words · ${oneDecimal(state.wordsPerSecond)} words/s"
        }

        fun summary(words: Int, seconds: Float, unsure: Int = 0): String =
            "$words words in ${seconds.roundToInt()} s · ${oneDecimal(words / max(seconds, 0.001f))} words/s" +
                if (unsure > 0) " · $unsure unsure" else ""

        private fun oneDecimal(x: Float) = String.format(Locale.ROOT, "%.1f", x)
    }
}
