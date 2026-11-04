package com.simonpena.unmuffle

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DownloadViewModel"

/** Gets the model: downloaded with DownloadManager, or from a file the user picks. */
class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    sealed class State {
        object Idle : State()
        /** [stalled]: still at 0 bytes after [ModelFile.STALL_MS]. */
        data class Downloading(val bytes: Long, val total: Long, val stalled: Boolean = false) : State()
        object Verifying : State()
        /** Copying and checking a picked file. */
        data class Checking(val bytes: Long, val total: Long) : State()
        object WrongFile : State()
        object Complete : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val context get() = getApplication<Application>()
    private val downloads get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var downloadId: Long? = null
    private var polling: Job? = null

    /** Downloads the selected model, on any network. */
    fun startDownload() {
        val target = ModelManager.modelFile(context)
        if (target.exists()) target.delete()
        val model = SettingsRepository.getModel(context)
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Unmuffle — Whisper Model")
            .setDescription("Downloading transcription model (${megabytes(model.sizeBytes)} MB)...")
            .setDestinationInExternalFilesDir(context, null, "models/${model.filename}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = downloads.enqueue(request)
        downloadId = id
        Log.i(TAG, "Download $id enqueued")
        polling = viewModelScope.launch { poll(id) }
    }

    /** Starts again after a failure. */
    fun retryDownload() {
        ModelManager.deleteModel(context)
        _state.value = State.Idle
        startDownload()
    }

    /** Copies the model from [uri] and checks it; a download under way is cancelled. */
    fun useFile(uri: Uri) {
        polling?.cancel()
        downloadId?.let { downloads.remove(it) }
        downloadId = null
        val model = SettingsRepository.getModel(context)
        val target = ModelManager.modelFile(context)
        _state.value = State.Checking(0, model.sizeBytes)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
                    ModelFile.copy(input, target, model.sha256) { _state.value = State.Checking(it, model.sizeBytes) }
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't read the picked file", e)
                    false
                }
            }
            if (ok) {
                ModelManager.deleteObsoleteModels(context)
                _state.value = State.Complete
            } else {
                _state.value = State.WrongFile
            }
        }
    }

    private suspend fun poll(id: Long) {
        val startMs = SystemClock.elapsedRealtime()
        while (true) {
            downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            _state.value = State.Verifying
                            verify()
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            Log.e(TAG, "Download failed: $reason")
                            ModelManager.deleteModel(context)
                            _state.value = State.Failed("Download failed (code $reason). Tap Retry.")
                            return
                        }
                        else -> _state.value = State.Downloading(
                            bytes, total, ModelFile.stalled(bytes, SystemClock.elapsedRealtime() - startMs),
                        )
                    }
                }
            }
            delay(500)
        }
    }

    private suspend fun verify() {
        if (ModelManager.isModelReady(context)) {
            ModelManager.deleteObsoleteModels(context)
            _state.value = State.Complete
        } else {
            Log.e(TAG, "SHA-256 mismatch")
            ModelManager.deleteModel(context)
            _state.value = State.Failed("Download corrupt. SHA256 mismatch. Tap Retry.")
        }
    }
}
