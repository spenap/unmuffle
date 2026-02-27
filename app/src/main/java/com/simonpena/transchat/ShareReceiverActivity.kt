package com.simonpena.transchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "ShareReceiver"

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

        // Launch decode coroutine — Phase 2 Plan 02 will replace this with AudioDecoder pipeline
        lifecycleScope.launch {
            Log.i(TAG, "Audio cached: ${cachedAudioFile!!.absolutePath} (${cachedAudioFile!!.length()} bytes)")
            Toast.makeText(
                this@ShareReceiverActivity,
                "Audio received (${cachedAudioFile!!.length() / 1024}KB)",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
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
}
