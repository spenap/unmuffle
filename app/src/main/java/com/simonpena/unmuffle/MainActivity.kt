package com.simonpena.unmuffle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The launcher: opens the download screen until the model is there, then the ready screen. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (ModelManager.isModelPresentFast(this)) ReadyActivity::class.java else DownloadActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
