package com.simonpena.unmuffle

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioDecoder"

/**
 * Decoded PCM output from [AudioDecoder].
 *
 * @property samples Interleaved int16 PCM at the native decoder output rate.
 * @property sampleRate Output sample rate (e.g. 48000 for Opus).
 * @property channels Number of audio channels (1 = mono, 2 = stereo).
 */
data class DecodedAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedAudio) return false
        return samples.contentEquals(other.samples) &&
            sampleRate == other.sampleRate &&
            channels == other.channels
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        return result
    }
}

/**
 * Decodes compressed audio files (OGG/Opus, MP3, etc.) to raw PCM using
 * platform MediaExtractor + MediaCodec.
 *
 * Uses the synchronous polling pattern: feed input buffers from MediaExtractor,
 * drain output buffers into a byte list, then convert to ShortArray.
 *
 * The Opus decoder always outputs at 48kHz regardless of the original recording
 * sample rate. Callers must check [DecodedAudio.sampleRate] and resample if needed.
 */
object AudioDecoder {

    /**
     * Decode the audio file at [filePath] to raw PCM.
     *
     * @param filePath Absolute path to an audio file (OGG/Opus, MP3, etc.)
     * @return [DecodedAudio] with int16 samples at the decoder's native rate,
     *         or null if no audio track found or decode fails.
     */
    fun decode(filePath: String): DecodedAudio? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)

            // Find the first audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "No audio track found in $filePath")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            Log.i(TAG, "Audio track: mime=$mime, format=$audioFormat")

            // Create and configure decoder
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val pcmChunks = mutableListOf<ByteArray>()

            // Defaults -- updated when INFO_OUTPUT_FORMAT_CHANGED fires
            var outputSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            while (!outputDone) {
                // Feed input buffers from extractor
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            // End of stream -- signal decoder
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output buffers
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // CRITICAL: read actual output format from decoder
                        val outFormat = codec.outputFormat
                        outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        Log.i(TAG, "Output format changed: ${outputSampleRate}Hz, ${outputChannels}ch")
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Timeout -- continue loop
                    }
                    @Suppress("DEPRECATION")
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // Deprecated since API 21 -- no-op
                    }
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outBuffer = codec.getOutputBuffer(outputIndex)!!
                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.get(chunk)
                            pcmChunks.add(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            codec = null
            extractor.release()
            extractor = null

            // Concatenate all byte chunks
            val totalSize = pcmChunks.sumOf { it.size }
            if (totalSize == 0) {
                Log.w(TAG, "Decode produced zero bytes for $filePath")
                return null
            }

            val totalBytes = ByteArray(totalSize)
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, totalBytes, offset, chunk.size)
                offset += chunk.size
            }

            // Convert to ShortArray: read pairs of bytes as little-endian int16
            val shorts = ShortArray(totalBytes.size / 2)
            ByteBuffer.wrap(totalBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

            Log.i(TAG, "Decoded: ${shorts.size} samples, ${outputSampleRate}Hz, ${outputChannels}ch from $filePath")
            return DecodedAudio(shorts, outputSampleRate, outputChannels)

        } catch (e: Exception) {
            Log.e(TAG, "Decode failed for $filePath: ${e.message}", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
