package com.simonpena.transchat

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher Activity — transparent router. Never shown to user directly.
 * Checks model presence and routes to DownloadActivity or ReadyActivity.
 *
 * Uses isModelPresentFast (no SHA256) for quick routing. Full SHA256 check
 * happens after download in DownloadViewModel. This avoids a 2-3s delay on
 * every app launch just to verify a 190MB file.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView — this Activity is purely a router
        routeToCorrectScreen()
    }

    private fun routeToCorrectScreen() {
        val destination = if (ModelManager.isModelPresentFast(this)) {
            Intent(this, ReadyActivity::class.java)
        } else {
            Intent(this, DownloadActivity::class.java)
        }
        startActivity(destination)
        finish()  // Remove MainActivity from back stack
    }
}
