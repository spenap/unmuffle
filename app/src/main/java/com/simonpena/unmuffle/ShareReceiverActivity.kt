package com.simonpena.unmuffle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The share target, without a window: copies the shared audio to the cache
 * and opens [TranscriptionActivity] on it. The copy happens in onCreate, as
 * the permission to read the content URI ends when the sending app leaves.
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ModelManager.isModelPresentFast(this)) {
            Toast.makeText(this, "Model needed", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, DownloadActivity::class.java))
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val uri = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        when (val result = uri?.let { AudioCopier.copyToCache(this, it) }) {
            is AudioCopier.CopyResult.Success -> startActivity(
                Intent(this, TranscriptionActivity::class.java)
                    .putExtra(TranscriptionActivity.EXTRA_AUDIO_PATH, result.file.absolutePath)
            )
            is AudioCopier.CopyResult.TooLarge -> Toast.makeText(
                this, "File too large (25MB max). Unmuffle works best with voice messages.", Toast.LENGTH_LONG,
            ).show()
            else -> Toast.makeText(this, "Could not process this file.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
