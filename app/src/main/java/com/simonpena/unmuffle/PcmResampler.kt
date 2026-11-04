package com.simonpena.unmuffle

import android.util.Log

private const val TAG = "PcmResampler"

/**
 * Converts decoded audio to what whisper takes: 16 kHz mono float in
 * [-1, 1]. Resamples by linear interpolation, without a low-pass filter:
 * voice messages carry almost nothing above 8 kHz.
 */
object PcmResampler {

    private const val WHISPER_SAMPLE_RATE = 16000

    /** [decoded] as 16 kHz mono float, or null if it is empty. */
    fun toWhisperFormat(decoded: DecodedAudio): FloatArray? {
        if (decoded.samples.isEmpty()) {
            Log.w(TAG, "Input samples are empty")
            return null
        }

        val inputSamples = decoded.samples.size
        var samples = decoded.samples

        if (decoded.channels == 2) {
            samples = stereoToMono(samples)
        }

        if (decoded.sampleRate != WHISPER_SAMPLE_RATE) {
            samples = resample(samples, decoded.sampleRate, WHISPER_SAMPLE_RATE)
        }

        val floats = shortToFloat(samples)

        val durationSeconds = floats.size / WHISPER_SAMPLE_RATE.toFloat()
        Log.i(
            TAG,
            "${decoded.channels}ch ${decoded.sampleRate}Hz -> 1ch ${WHISPER_SAMPLE_RATE}Hz, " +
                "$inputSamples -> ${floats.size} samples (${String.format("%.1f", durationSeconds)}s)"
        )

        return floats
    }

    private fun stereoToMono(stereo: ShortArray): ShortArray {
        val mono = ShortArray(stereo.size / 2)
        for (i in mono.indices) {
            mono[i] = ((stereo[i * 2].toInt() + stereo[i * 2 + 1].toInt()) / 2).toShort()
        }
        return mono
    }

    private fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return samples

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputSize = (samples.size / ratio).toInt()
        val output = ShortArray(outputSize)

        for (i in 0 until outputSize) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            val s0 = samples[srcIndex.coerceAtMost(samples.size - 1)].toInt()
            val s1 = samples[(srcIndex + 1).coerceAtMost(samples.size - 1)].toInt()
            output[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }

        return output
    }

    private fun shortToFloat(shorts: ShortArray): FloatArray {
        return FloatArray(shorts.size) { shorts[it].toFloat() / 32768.0f }
    }
}
