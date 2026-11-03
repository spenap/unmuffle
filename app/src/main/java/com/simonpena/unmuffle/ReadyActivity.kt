package com.simonpena.unmuffle

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Shown after successful model download (or on subsequent launches when model exists).
 * Instructs user how to use the share target.
 *
 * In Phase 3 this screen will be replaced or enhanced — for now it's a static
 * instructions screen confirming the download was successful.
 */
class ReadyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ready)
        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
    }
}
