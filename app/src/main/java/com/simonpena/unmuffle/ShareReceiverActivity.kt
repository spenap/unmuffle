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
            Toast.makeText(this, R.string.model_needed, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
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
                this, getString(R.string.file_too_large, AudioCopier.MAX_FILE_SIZE_BYTES / (1024 * 1024)), Toast.LENGTH_LONG,
            ).show()
            else -> Toast.makeText(this, R.string.file_unreadable, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
