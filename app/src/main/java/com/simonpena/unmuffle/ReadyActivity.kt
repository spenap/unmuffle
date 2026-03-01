package com.simonpena.unmuffle

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

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
    }
}
