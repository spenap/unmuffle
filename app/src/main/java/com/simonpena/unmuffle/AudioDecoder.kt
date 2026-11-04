package com.simonpena.unmuffle

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioDecoder"

/** Interleaved int16 PCM at the decoder's rate (48 kHz for Opus). */
class DecodedAudio(val samples: ShortArray, val sampleRate: Int, val channels: Int)

/** Decodes an audio file's first audio track (Opus, AAC, MP3…) with the platform's MediaCodec. */
object AudioDecoder {

    /** The decoded audio, or null if the file has no audio track or decoding fails. */
    fun decode(filePath: String): DecodedAudio? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)

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

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val pcmChunks = mutableListOf<ByteArray>()

            // The decoder's output format can differ from the track's
            var outputSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
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

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        Log.i(TAG, "Output format changed: ${outputSampleRate}Hz, ${outputChannels}ch")
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
