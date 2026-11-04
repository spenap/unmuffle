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
import kotlinx.coroutines.launch

class DownloadActivity : AppCompatActivity() {

    private val viewModel: DownloadViewModel by viewModels()

    private lateinit var tvExplainer: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnDownload: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)

        tvExplainer = findViewById(R.id.tv_explainer)
        progressBar = findViewById(R.id.progress_bar)
        tvProgress = findViewById(R.id.tv_progress)
        btnDownload = findViewById(R.id.btn_download)
        tvStatus = findViewById(R.id.tv_status)

        btnDownload.setOnClickListener { viewModel.startDownload() }

        lifecycleScope.launch {
            viewModel.state.collect { state -> renderState(state) }
        }
    }

    private fun renderState(state: DownloadViewModel.State) {
        when (state) {
            is DownloadViewModel.State.Idle -> {
                btnDownload.isEnabled = true
                btnDownload.text = "Download (${SettingsRepository.getModel(this).sizeBytes / 1_048_576} MB)"
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
                tvProgress.text = "${formatBytes(state.bytesDownloaded)} of ${formatBytes(state.totalBytes)}"
                tvStatus.visibility = View.GONE
            }
            is DownloadViewModel.State.Verifying -> {
                btnDownload.isEnabled = false
                btnDownload.text = "Verifying..."
                progressBar.progress = 100
                tvProgress.text = "Verifying download integrity..."
                tvStatus.visibility = View.GONE
            }
            is DownloadViewModel.State.Complete -> {
                startActivity(Intent(this, ReadyActivity::class.java))
                finish()
            }
            is DownloadViewModel.State.Failed -> {
                btnDownload.isEnabled = true
                btnDownload.text = "Retry"
                progressBar.visibility = View.GONE
                tvProgress.visibility = View.GONE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = state.reason
                btnDownload.setOnClickListener { viewModel.retryDownload() }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
    }
}
