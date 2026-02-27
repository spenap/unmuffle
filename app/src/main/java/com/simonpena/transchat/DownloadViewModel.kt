package com.simonpena.transchat

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "DownloadViewModel"

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    sealed class State {
        object Idle : State()
        data class Downloading(
            val progressFraction: Float,   // 0.0 - 1.0
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : State()
        object Verifying : State()
        object Complete : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** True if device is on a cellular-only connection (no WiFi). */
    fun isOnCellularOnly(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Enqueue the model download via Android DownloadManager.
     *
     * @param allowCellular Set to true if user confirmed the cellular warning dialog.
     */
    fun startDownload(allowCellular: Boolean = false) {
        val context = getApplication<Application>()
        val destFile = ModelManager.modelFile(context)

        // Delete any partial file from a previous failed attempt
        if (destFile.exists()) destFile.delete()

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val networkTypes = if (allowCellular) {
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
        } else {
            DownloadManager.Request.NETWORK_WIFI
        }

        val request = DownloadManager.Request(Uri.parse(ModelManager.MODEL_URL))
            .setTitle("TransChat — Whisper Model")
            .setDescription("Downloading transcription model (190 MB)...")
            .setDestinationUri(Uri.fromFile(destFile))
            .setAllowedNetworkTypes(networkTypes)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

        val downloadId = downloadManager.enqueue(request)
        Log.i(TAG, "Download enqueued with id=$downloadId to ${destFile.absolutePath}")

        // Poll DownloadManager every 500ms — there is no callback API
        viewModelScope.launch {
            pollDownloadProgress(downloadManager, downloadId, destFile)
        }
    }

    /** Retry download after failure — resets state to Idle then starts again. */
    fun retryDownload(allowCellular: Boolean = false) {
        val context = getApplication<Application>()
        ModelManager.deleteModel(context)
        _state.value = State.Idle
        startDownload(allowCellular)
    }

    private suspend fun pollDownloadProgress(
        downloadManager: DownloadManager,
        downloadId: Long,
        destFile: File
    ) {
        while (true) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val totalBytes = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )

                    when (status) {
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                            val fraction = if (totalBytes > 0) {
                                bytesDownloaded.toFloat() / totalBytes.toFloat()
                            } else 0f
                            _state.value = State.Downloading(fraction, bytesDownloaded, totalBytes)
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.i(TAG, "Download complete — verifying SHA256")
                            _state.value = State.Verifying
                            verifyAndComplete(destFile)
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            Log.e(TAG, "Download failed with reason=$reason")
                            ModelManager.deleteModel(getApplication())
                            _state.value = State.Failed("Download failed (code $reason). Tap Retry.")
                            return
                        }
                    }
                }
            }
            delay(500)
        }
    }

    private suspend fun verifyAndComplete(destFile: File) {
        val context = getApplication<Application>()
        val isValid = ModelManager.isModelReady(context)
        if (isValid) {
            Log.i(TAG, "SHA256 verified — model ready")
            _state.value = State.Complete
        } else {
            Log.e(TAG, "SHA256 mismatch — deleting corrupt model")
            ModelManager.deleteModel(context)
            _state.value = State.Failed("Download corrupt. SHA256 mismatch. Tap Retry.")
        }
    }
}
