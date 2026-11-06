package com.simonpena.unmuffle

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/** Home: getting the model (a download, or a file) until it is in place, then how to share a voice message. */
class MainActivity : AppCompatActivity() {

    private val viewModel: DownloadViewModel by viewModels()
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.useFile(uri)
    }

    private val progressBar by lazy { findViewById<ProgressBar>(R.id.progress_bar) }
    private val tvProgress by lazy { findViewById<TextView>(R.id.tv_progress) }
    private val btnDownload by lazy { findViewById<Button>(R.id.btn_download) }
    private val btnUseFile by lazy { findViewById<View>(R.id.btn_use_file) }
    private val stalledCard by lazy { findViewById<View>(R.id.stalled_card) }
    private val tvStatus by lazy { findViewById<TextView>(R.id.tv_status) }
    private val model by lazy { SettingsRepository.getModel(this) }

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
        btnUseFile.setOnClickListener { pickModelFile() }
        findViewById<View>(R.id.btn_pick_file).setOnClickListener { pickModelFile() }
        lifecycleScope.launch { viewModel.state.collect { show(it) } }
    }

    private fun show(state: DownloadViewModel.State) {
        // What most states share; each case below changes what differs.
        btnDownload.isEnabled = false
        btnUseFile.visibility = View.GONE
        stalledCard.visibility = View.GONE
        tvStatus.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        when (state) {
            is DownloadViewModel.State.Idle, is DownloadViewModel.State.WrongFile -> {
                offerDownload(getString(R.string.download_size, megabytes(model.sizeBytes))) { viewModel.startDownload() }
                if (state is DownloadViewModel.State.WrongFile) {
                    showStatus(getString(R.string.wrong_file, model.filename))
                }
            }
            is DownloadViewModel.State.Downloading -> {
                btnDownload.setText(R.string.downloading)
                progressBar.progress = percent(state.bytes, state.total)
                tvProgress.text = if (state.bytes > 0) of(state.bytes, state.total) else getString(R.string.waiting_to_start)
                if (state.stalled) stalledCard.visibility = View.VISIBLE
            }
            is DownloadViewModel.State.Verifying -> {
                btnDownload.setText(R.string.verifying)
                progressBar.progress = 100
                tvProgress.setText(R.string.verifying_download)
            }
            is DownloadViewModel.State.Checking -> {
                btnDownload.setText(R.string.checking_model)
                progressBar.progress = percent(state.bytes, state.total)
                tvProgress.text = getString(
                    R.string.checking_progress, model.filename, megabytes(state.bytes), megabytes(state.total),
                )
            }
            is DownloadViewModel.State.Complete -> showReady()
            is DownloadViewModel.State.Failed -> {
                offerDownload(getString(R.string.retry)) { viewModel.retryDownload() }
                showStatus(state.code?.let { getString(R.string.download_failed, it) } ?: getString(R.string.download_corrupt))
            }
        }
    }

    /** The download button enabled, with the file option beside it. */
    private fun offerDownload(label: String, onClick: () -> Unit) {
        btnDownload.isEnabled = true
        btnDownload.text = label
        btnDownload.setOnClickListener { onClick() }
        btnUseFile.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        tvProgress.visibility = View.GONE
    }

    private fun showStatus(text: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = text
    }

    private fun pickModelFile() = pickFile.launch(arrayOf("*/*"))

    private fun showReady() {
        findViewById<View>(R.id.download_group).visibility = View.GONE
        findViewById<View>(R.id.ready_group).visibility = View.VISIBLE
    }

    private fun of(bytes: Long, total: Long) = getString(R.string.megabytes_of, megabytes(bytes), megabytes(total))
    private fun percent(bytes: Long, total: Long) = if (total > 0) (bytes * 100 / total).toInt() else 0
}
