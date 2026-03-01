package com.simonpena.unmuffle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

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
 *
 * After copying the audio to cache, this activity launches [TranscriptionActivity]
 * with the cached file path and finishes immediately. The [TranscriptionViewModel]
 * owns the cached file and cleans it up in onCleared().
 */
class ShareReceiverActivity : AppCompatActivity() {

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
                    "File too large (25MB max). Unmuffle works best with voice messages.",
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
                // Launch TranscriptionActivity with cached file path
                val transcriptionIntent = Intent(this, TranscriptionActivity::class.java)
                transcriptionIntent.putExtra(
                    TranscriptionActivity.EXTRA_AUDIO_PATH,
                    result.file.absolutePath
                )
                startActivity(transcriptionIntent)
                finish()
                return
            }
        }
    }

    companion object {
        private const val TAG = "ShareReceiver"
    }
}
