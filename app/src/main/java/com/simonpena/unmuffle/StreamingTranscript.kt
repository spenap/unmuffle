package com.simonpena.unmuffle

/**
 * The transcription screen's state while whisper.cpp runs: progress and the
 * segments decoded so far, without non-speech markers. The final transcript
 * still goes through [HallucinationFilter].
 */
class StreamingTranscript {
    private val text = StringBuilder()

    var state = UiState.Processing()
        private set

    fun progress(percent: Int): UiState.Processing {
        state = state.copy(progress = percent)
        return state
    }

    fun segment(segment: String): UiState.Processing {
        val speech = HallucinationFilter.MARKER_REGEX.replace(segment, "").trim()
        if (speech.isNotEmpty()) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(speech)
            state = state.copy(partial = text.toString())
        }
        return state
    }
}
