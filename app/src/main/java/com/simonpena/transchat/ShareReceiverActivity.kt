package com.simonpena.transchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transparent share target that receives audio files from other apps.
 *
 * Registered in the manifest with two intent-filters:
 * one for all audio MIME types (WhatsApp, most apps) and one for
 * application/octet-stream (Telegram voice messages).
 *
 * All byte acquisition happens synchronously in onCreate before any coroutine
 * launch, because the content URI permission expires when the sending app is
 * backgrounded.
 */
class ShareReceiverActivity : AppCompatActivity() {

    private var cachedAudioFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView — transparent theme, no visible window

        // Guard: only handle ACTION_SEND
        if (intent?.action != Intent.ACTION_SEND) {
            Toast.makeText(this, "Could not process this file.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Model check: redirect to download if model not present
        if (!ModelManager.isModelPresentFast(this)) {
            Toast.makeText(this, "Model needed", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, DownloadActivity::class.java))
            finish()
            return
        }

        // Extract the shared content URI
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "Could not process this file.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Synchronous copy — MUST happen before any async work
        when (val result = AudioCopier.copyToCache(this, uri)) {
            is AudioCopier.CopyResult.TooLarge -> {
                Toast.makeText(
                    this,
                    "File too large (25MB max). TransChat works best with voice messages.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
            is AudioCopier.CopyResult.NoContent -> {
                Toast.makeText(this, "Could not process this file.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            is AudioCopier.CopyResult.CopyFailed -> {
                Toast.makeText(this, "Could not process this file.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            is AudioCopier.CopyResult.Success -> {
                cachedAudioFile = result.file
            }
        }

        Log.i(TAG, "Audio cached: ${cachedAudioFile!!.absolutePath} (${cachedAudioFile!!.length()} bytes)")

        // Launch decode coroutine on background thread
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                decodeToWhisperPcm(cachedAudioFile!!)
            }
            if (result == null) {
                Toast.makeText(this@ShareReceiverActivity, "Could not process this file.", Toast.LENGTH_SHORT).show()
            } else {
                val durationSec = result.size / 16000f
                Log.i(TAG, "Decode complete: ${result.size} samples (${durationSec}s) at 16kHz mono float32")
                // Phase 3 will pass 'result' to WhisperEngine.transcribe() here
                Toast.makeText(
                    this@ShareReceiverActivity,
                    "Audio decoded (${String.format("%.1f", durationSec)}s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }
    }

    /**
     * Decode compressed audio to 16kHz mono float32 PCM for Whisper.
     *
     * Pipeline: MediaCodec decode -> verification WAV at native rate ->
     * resample to 16kHz mono float32 -> verification WAV at 16kHz.
     *
     * @return FloatArray ready for WhisperEngine.transcribe(), or null on failure.
     */
    private fun decodeToWhisperPcm(audioFile: File): FloatArray? {
        // Step 1: Decode compressed audio to raw PCM
        val decoded = AudioDecoder.decode(audioFile.absolutePath)
        if (decoded == null) {
            Log.e(TAG, "AudioDecoder.decode failed for ${audioFile.name}")
            return null
        }
        Log.i(TAG, "Decoded: ${decoded.samples.size} samples, ${decoded.sampleRate}Hz, ${decoded.channels}ch")

        // Step 2: Write verification WAV BEFORE resampling (original decoded rate)
        // This WAV can be adb-pulled and played on a computer to verify decode quality
        val wavFile = File(cacheDir, "audio/verify_${System.currentTimeMillis()}.wav")
        WavWriter.write(wavFile, decoded.samples, decoded.sampleRate, decoded.channels)
        Log.i(TAG, "Verification WAV written: ${wavFile.absolutePath} (${wavFile.length() / 1024}KB)")

        // Step 3: Resample to 16kHz mono float32 (Whisper input format)
        val pcm = PcmResampler.toWhisperFormat(decoded)
        if (pcm == null) {
            Log.e(TAG, "PcmResampler.toWhisperFormat returned null")
            return null
        }

        // Step 4: Write a second verification WAV at 16kHz for Whisper-ready verification
        val resampled16 = ShortArray(pcm.size) { (pcm[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
        val wavFile16 = File(cacheDir, "audio/verify_16khz_${System.currentTimeMillis()}.wav")
        WavWriter.write(wavFile16, resampled16, 16000, 1)
        Log.i(TAG, "16kHz verification WAV: ${wavFile16.absolutePath} (${wavFile16.length() / 1024}KB)")

        return pcm
    }

    override fun onDestroy() {
        cachedAudioFile?.let { file ->
            if (file.exists()) {
                val deleted = file.delete()
                Log.i(TAG, "Cached audio file deleted: $deleted (${file.absolutePath})")
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShareReceiver"
    }
}
