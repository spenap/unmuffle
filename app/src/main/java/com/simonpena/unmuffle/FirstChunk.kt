package com.simonpena.unmuffle

/**
 * With a language set, the first ~10 s are transcribed alone with a window
 * that fits them, as a preview: the first words come sooner. The whole
 * message is then transcribed as without it, and replaces the preview.
 * Auto-detect has no preview.
 */
object FirstChunk {
    private const val RATE = 16000
    private const val SECONDS = 10.0
    private const val QUIET = RATE / 10 // 100 ms
    private const val STEP = RATE / 100 // 10 ms
    private const val FRAMES_PER_SECOND = 50 // whisper's encoder
    private const val MAX_FRAMES = 1500

    /** Where to cut [pcm]: the middle of its quietest 100 ms 8–12 s in, or null to keep it whole. */
    fun cut(pcm: FloatArray, language: String): Int? {
        if (language == Languages.AUTO || pcm.size <= 1.5 * SECONDS * RATE) return null
        var best = (SECONDS * RATE).toInt()
        var lowest = Double.MAX_VALUE
        var start = (0.8 * SECONDS * RATE).toInt()
        while (start + QUIET <= 1.2 * SECONDS * RATE) {
            var energy = 0.0
            for (i in start until start + QUIET) energy += pcm[i] * pcm[i]
            if (energy < lowest) {
                lowest = energy
                best = start + QUIET / 2
            }
            start += STEP
        }
        return best
    }

    /** The encoder window for [samples], with some margin; 0 for whisper's full window. */
    fun audioCtx(samples: Int): Int =
        (samples.toLong() * FRAMES_PER_SECOND / RATE + 64).toInt().let { if (it >= MAX_FRAMES) 0 else it }
}
