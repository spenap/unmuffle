package com.simonpena.unmuffle

/**
 * Tells whisper's output on silence or noise from speech: only non-speech
 * markers ([BLANK_AUDIO], (music)…), or a few words repeated over and over.
 */
object HallucinationFilter {

    // Known non-speech markers that whisper.cpp outputs for silence/noise
    private val NON_SPEECH_MARKERS = listOf(
        "[BLANK_AUDIO]",
        "[MUSIC]",
        "[SILENCE]",
        "(BEEPING)",
        "(MUSIC)",
        "(music)",
    )

    // Regex for markers in square brackets or parentheses that contain no speech
    internal val MARKER_REGEX = Regex("""\[[\w\s_]+\]|\([\w\s_]+\)""")

    /** The trimmed transcript, or null if it isn't speech. */
    fun filter(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // Check for known non-speech markers
        if (NON_SPEECH_MARKERS.any { trimmed.contains(it, ignoreCase = true) }) {
            // If the entire output is just markers, it's non-speech
            val withoutMarkers = MARKER_REGEX.replace(trimmed, "").trim()
            if (withoutMarkers.isBlank()) return null
        }

        // Check for repetitive hallucination: same short phrase repeated many times
        val words = trimmed.split("\\s+".toRegex())
        if (words.size >= 6) {
            val uniqueWords = words.toSet()
            // If fewer than 1/3 unique words, likely hallucination
            if (uniqueWords.size.toFloat() / words.size < 0.33f) return null
        }

        return trimmed
    }
}
