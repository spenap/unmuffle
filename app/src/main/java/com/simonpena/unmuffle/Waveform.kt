package com.simonpena.unmuffle

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The voice bubble's waveform and times. */
object Waveform {
    /** The peak of each of [count] equal stretches of [samples], scaled so the loudest is 1. */
    fun bars(samples: FloatArray, count: Int): FloatArray {
        val bars = FloatArray(count)
        if (samples.isEmpty()) return bars
        for (i in 0 until count) {
            val start = min(i * samples.size / count, samples.size - 1)
            val end = max(start + 1, (i + 1) * samples.size / count)
            for (j in start until end) bars[i] = max(bars[i], abs(samples[j]))
        }
        val loudest = bars.max()
        if (loudest > 0f) for (i in bars.indices) bars[i] /= loudest
        return bars
    }

    /** "m:ss", rounded down. */
    fun time(seconds: Float): String {
        val s = seconds.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}
