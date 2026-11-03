package com.simonpena.unmuffle

/**
 * Post-processing filter for Whisper inference output.
 *
 * Detects non-speech markers ([BLANK_AUDIO], [MUSIC], etc.) and repetitive
 * hallucination patterns (same words repeated many times) that the q5_1
 * quantized model sometimes produces on silence or noise.
 *
 * This is a pure Kotlin post-processing filter -- it does NOT modify any
 * whisper.cpp params. The default whisper params with timestamp-based
 * segmentation are kept because no_timestamps=true caused the q5_1 model
 * to filter real speech (documented in STATE.md).
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

    /**
     * Filter whisper output, returning null if no meaningful speech was detected.
     *
     * @param raw Raw transcript from WhisperEngine.transcribe()
     * @return Cleaned transcript text, or null if input is non-speech
     */
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
