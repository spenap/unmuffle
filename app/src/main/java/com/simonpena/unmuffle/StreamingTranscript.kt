package com.simonpena.unmuffle

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The transcription screen's state while whisper.cpp runs: progress, the
 * segments decoded so far and the words being decoded after them (without
 * non-speech markers), how many words are
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
    private var decoding: List<Word> = emptyList()

    var state = UiState.Processing(placeholders = remaining(-1))
        private set

    fun progress(percent: Int): UiState.Processing {
        state = state.copy(progress = percent, placeholders = remaining(percent), wordsPerSecond = rate())
        return state
    }

    /** Appends a segment's words, without non-speech markers such as "[BLANK_AUDIO]" or "(music)". */
    fun segment(segment: List<Word>): UiState.Processing {
        decoding = emptyList()
        for (word in speech(segment)) {
            if (text.isNotEmpty()) text.append(' ')
            if (word.p < UNSURE_BELOW) unsure += text.length until text.length + word.text.length
            text.append(word.text)
            words++
        }
        return show()
    }

    /** Shows the words being decoded after the segments; the next segment replaces them. */
    fun decoding(words: List<Word>): UiState.Processing {
        decoding = speech(words)
        return show()
    }

    private fun show(): UiState.Processing {
        val shown = StringBuilder(text)
        val shownUnsure = unsure.toMutableList()
        for (word in decoding) {
            if (shown.isNotEmpty()) shown.append(' ')
            if (word.p < UNSURE_BELOW) shownUnsure += shown.length until shown.length + word.text.length
            shown.append(word.text)
        }
        state = state.copy(
            partial = shown.toString(), words = shownWords(), unsure = shownUnsure,
            placeholders = remaining(state.progress), wordsPerSecond = rate(),
        )
        return state
    }

    private fun shownWords() = words + decoding.size

    private fun speech(words: List<Word>): List<Word> {
        var inMarker = false
        return words.filter { word ->
            if (word.text.startsWith('[') || word.text.startsWith('(')) inMarker = true
            val keep = !inMarker
            if (word.text.endsWith(']') || word.text.endsWith(')')) inMarker = false
            keep
        }
    }

    fun elapsedSeconds(): Float = (clock() - startMs) / 1000f

    private fun rate(): Float? {
        val seconds = elapsedSeconds()
        return if (shownWords() == 0 || seconds <= 0f) null else shownWords() / seconds
    }

    private fun remaining(percent: Int): Int = when {
        percent >= 100 -> 0
        shownWords() > 0 && percent > 0 -> (shownWords() * (100 - percent) / percent.toFloat()).roundToInt()
        else -> max(0, (audioSeconds * (100 - max(percent, 0)) / 100 * SPEECH_WORDS_PER_SECOND).roundToInt() - shownWords())
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
