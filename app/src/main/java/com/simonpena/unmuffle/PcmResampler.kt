package com.simonpena.unmuffle

import android.util.Log

private const val TAG = "PcmResampler"

/**
 * Resamples decoded PCM audio to the format expected by whisper.cpp:
 * 16kHz, mono, float32 normalized to [-1.0, 1.0].
 *
 * Pipeline: stereo-to-mono (if needed) -> resample (if needed) -> int16-to-float32.
 *
 * Uses linear interpolation for resampling, which handles any input rate
 * (48kHz, 44.1kHz, etc.) with a single code path.
 */
object PcmResampler {

    private const val WHISPER_SAMPLE_RATE = 16000

    /**
     * Convert [decoded] audio to 16kHz mono float32 for Whisper.
     *
     * @return FloatArray of normalized samples, or null if input is empty.
     */
    fun toWhisperFormat(decoded: DecodedAudio): FloatArray? {
        if (decoded.samples.isEmpty()) {
            Log.w(TAG, "Input samples are empty")
            return null
        }

        val inputSamples = decoded.samples.size
        var samples = decoded.samples

        // Step 1: Mix stereo to mono if needed
        if (decoded.channels == 2) {
            samples = stereoToMono(samples)
        }

        // Step 2: Resample to 16kHz if needed
        if (decoded.sampleRate != WHISPER_SAMPLE_RATE) {
            samples = resample(samples, decoded.sampleRate, WHISPER_SAMPLE_RATE)
        }

        // Step 3: Convert int16 to float32 normalized to [-1.0, 1.0]
        val floats = shortToFloat(samples)

        val durationSeconds = floats.size / WHISPER_SAMPLE_RATE.toFloat()
        Log.i(
            TAG,
            "${decoded.channels}ch ${decoded.sampleRate}Hz -> 1ch ${WHISPER_SAMPLE_RATE}Hz, " +
                "$inputSamples -> ${floats.size} samples (${String.format("%.1f", durationSeconds)}s)"
        )

        return floats
    }

    /**
     * Mix interleaved stereo int16 samples to mono by averaging L/R pairs.
     */
    private fun stereoToMono(stereo: ShortArray): ShortArray {
        val mono = ShortArray(stereo.size / 2)
        for (i in mono.indices) {
            mono[i] = ((stereo[i * 2].toInt() + stereo[i * 2 + 1].toInt()) / 2).toShort()
        }
        return mono
    }

    /**
     * Resample audio from [fromRate] to [toRate] using linear interpolation.
     *
     * Handles any input rate: 48kHz->16kHz (ratio 3.0), 44.1kHz->16kHz
     * (ratio ~2.756), and all other rates.
     */
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

    /**
     * Convert int16 PCM to float32 normalized to [-1.0, 1.0].
     */
    private fun shortToFloat(shorts: ShortArray): FloatArray {
        return FloatArray(shorts.size) { shorts[it].toFloat() / 32768.0f }
    }
}
