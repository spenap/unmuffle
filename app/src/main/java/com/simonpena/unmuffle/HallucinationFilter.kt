package com.simonpena.unmuffle

/**
 * Tells whisper's output on silence or noise from speech. Non-speech markers
 * ([BLANK_AUDIO], (music)…) are already gone ([StreamingTranscript]); what
 * remains is nothing, or a few words repeated over and over.
 */
object HallucinationFilter {
    private val WHITESPACE = Regex("\\s+")

    fun isSpeech(text: String): Boolean {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // Fewer than a third of the words different: a repetition loop
        return words.size < 6 || words.toSet().size.toFloat() / words.size >= 0.33f
    }
}
