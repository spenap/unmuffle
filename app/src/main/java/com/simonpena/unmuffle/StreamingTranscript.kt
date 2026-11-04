package com.simonpena.unmuffle

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The transcription screen's state while whisper.cpp runs: progress, the
 * segments decoded so far (without non-speech markers) and how many words
 * are probably still to come. The final
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
        state = state.copy(progress = percent, placeholders = remaining(percent))
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
            placeholders = remaining(state.progress),
        )
        return state
    }

    /** Clears the text so far: a preview, replaced by the segments that follow. */
    fun startOver(): UiState.Processing {
        text.setLength(0)
        words = 0
        unsure.clear()
        state = state.copy(partial = "", words = 0, unsure = emptyList())
        return state
    }

    fun elapsedSeconds(): Float = (clock() - startMs) / 1000f

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

        /** For the log: words, seconds, words per second and unsure words. */
        fun summary(words: Int, seconds: Float, unsure: Int = 0): String =
            "$words words in ${seconds.roundToInt()} s · ${oneDecimal(words / max(seconds, 0.001f))} words/s" +
                if (unsure > 0) " · $unsure unsure" else ""

        private fun oneDecimal(x: Float) = String.format(Locale.ROOT, "%.1f", x)
    }
}
