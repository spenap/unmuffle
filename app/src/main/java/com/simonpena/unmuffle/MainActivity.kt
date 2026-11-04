package com.simonpena.unmuffle

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/** Home: the model download until the model is in place, then how to share a voice message. */
class MainActivity : AppCompatActivity() {

    private val viewModel: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        if (ModelManager.isModelPresentFast(this)) {
            showReady()
            return
        }
        findViewById<View>(R.id.download_group).visibility = View.VISIBLE
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)
        val tvProgress = findViewById<TextView>(R.id.tv_progress)
        val btnDownload = findViewById<Button>(R.id.btn_download)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val megabytes = SettingsRepository.getModel(this).sizeBytes / 1_048_576

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is DownloadViewModel.State.Idle -> {
                        btnDownload.isEnabled = true
                        btnDownload.text = "Download ($megabytes MB)"
                        btnDownload.setOnClickListener { viewModel.startDownload() }
                        progressBar.visibility = View.GONE
                        tvProgress.visibility = View.GONE
                        tvStatus.visibility = View.GONE
                    }
                    is DownloadViewModel.State.Downloading -> {
                        btnDownload.isEnabled = false
                        btnDownload.text = "Downloading..."
                        progressBar.visibility = View.VISIBLE
                        progressBar.max = 100
                        progressBar.progress = (state.progressFraction * 100).toInt()
                        tvProgress.visibility = View.VISIBLE
                        tvProgress.text = "${megabytesOf(state.bytesDownloaded)} of ${megabytesOf(state.totalBytes)}"
                        tvStatus.visibility = View.GONE
                    }
                    is DownloadViewModel.State.Verifying -> {
                        btnDownload.isEnabled = false
                        btnDownload.text = "Verifying..."
                        progressBar.progress = 100
                        tvProgress.text = "Verifying download integrity..."
                    }
                    is DownloadViewModel.State.Complete -> showReady()
                    is DownloadViewModel.State.Failed -> {
                        btnDownload.isEnabled = true
                        btnDownload.text = "Retry"
                        btnDownload.setOnClickListener { viewModel.retryDownload() }
                        progressBar.visibility = View.GONE
                        tvProgress.visibility = View.GONE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = state.reason
                    }
                }
            }
        }
    }

    private fun showReady() {
        findViewById<View>(R.id.download_group).visibility = View.GONE
        findViewById<View>(R.id.ready_group).visibility = View.VISIBLE
    }

    private fun megabytesOf(bytes: Long) = "%.1f MB".format(maxOf(0L, bytes) / 1_048_576.0)
}
