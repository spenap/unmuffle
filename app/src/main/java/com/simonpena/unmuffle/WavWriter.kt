package com.simonpena.unmuffle

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File

private const val TAG = "WavWriter"

/**
 * Writes int16 PCM samples to a standard 44-byte RIFF/WAV file.
 *
 * Used for verification: decode audio, write WAV to cacheDir, adb pull,
 * and play on a computer to confirm decode quality.
 */
object WavWriter {

    /**
     * Write [samples] as a WAV file to [file].
     *
     * Creates parent directories if they do not exist.
     *
     * @param file Destination file path.
     * @param samples Int16 PCM samples (interleaved if multi-channel).
     * @param sampleRate Sample rate in Hz (e.g. 48000, 16000).
     * @param channels Number of audio channels (default 1 = mono).
     */
    fun write(file: File, samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        file.parentFile?.mkdirs()

        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = samples.size * 2 // 2 bytes per int16 sample

        try {
            BufferedOutputStream(file.outputStream()).use { out ->
                // RIFF header
                out.write("RIFF".toByteArray())
                out.write(intToLittleEndian(36 + dataSize))
                out.write("WAVE".toByteArray())

                // fmt sub-chunk
                out.write("fmt ".toByteArray())
                out.write(intToLittleEndian(16))                  // SubChunk1Size (PCM = 16)
                out.write(shortToLittleEndian(1))                 // AudioFormat (PCM = 1)
                out.write(shortToLittleEndian(channels))
                out.write(intToLittleEndian(sampleRate))
                out.write(intToLittleEndian(byteRate))
                out.write(shortToLittleEndian(blockAlign))
                out.write(shortToLittleEndian(bitsPerSample))

                // data sub-chunk
                out.write("data".toByteArray())
                out.write(intToLittleEndian(dataSize))

                // Write raw int16 samples in little-endian
                for (sample in samples) {
                    out.write(shortToLittleEndian(sample.toInt()))
                }
            }

            Log.i(TAG, "WAV written: ${file.absolutePath} (${file.length() / 1024}KB, ${sampleRate}Hz, ${channels}ch)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV: ${file.absolutePath}", e)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )

    private fun shortToLittleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
}
