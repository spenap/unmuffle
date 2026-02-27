package com.simonpena.transchat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "TranscriptionVM"

/**
 * UI state for the transcription screen.
 *
 * Observed by [TranscriptionActivity] via [TranscriptionViewModel.uiState].
 */
sealed class UiState {
    object Processing : UiState()
    data class Success(val transcript: String) : UiState()
    data class NoSpeech(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

/**
 * Manages the full transcription pipeline: model load, audio decode,
 * resample, Whisper inference, and hallucination filtering.
 *
 * Threading: The entire decode + transcribe pipeline runs on Dispatchers.Default.
 * WhisperEngine.loadModel() and WhisperEngine.transcribe() also use
 * Dispatchers.Default internally -- nested dispatching is fine with coroutines.
 *
 * Lifecycle: The ViewModel survives configuration changes. If the Activity
 * is recreated (rotation), the existing uiState is observed without re-running
 * inference. The ViewModel cleans up the cached audio file in [onCleared].
 */
class TranscriptionViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Processing)
    val uiState: StateFlow<UiState> = _uiState

    private val engine = WhisperEngine()
    private var inferenceJob: Job? = null
    private var audioFilePath: String? = null

    /**
     * Start the transcription pipeline for the audio file at [audioPath].
     *
     * Pipeline: load model -> decode audio -> resample to 16kHz -> transcribe -> filter.
     *
     * Call once from Activity.onCreate() guarded by savedInstanceState == null.
     */
    fun startTranscription(audioPath: String) {
        audioFilePath = audioPath

        inferenceJob = viewModelScope.launch {
            try {
                // Step 1: Load model
                val modelPath = ModelManager.modelFile(getApplication()).absolutePath
                Log.i(TAG, "Loading model from $modelPath")
                val loaded = engine.loadModel(modelPath)
                if (!loaded) {
                    _uiState.value = UiState.Error("Could not load model")
                    return@launch
                }

                // Step 2: Decode audio on background thread
                val decoded = withContext(Dispatchers.Default) {
                    AudioDecoder.decode(audioPath)
                }
                if (decoded == null) {
                    _uiState.value = UiState.Error("Could not decode audio")
                    return@launch
                }
                Log.i(TAG, "Decoded: ${decoded.samples.size} samples, ${decoded.sampleRate}Hz, ${decoded.channels}ch")

                // Step 3: Resample to 16kHz mono float32
                val pcm = withContext(Dispatchers.Default) {
                    PcmResampler.toWhisperFormat(decoded)
                }
                if (pcm == null) {
                    _uiState.value = UiState.Error("Could not resample audio")
                    return@launch
                }
                Log.i(TAG, "Resampled: ${pcm.size} samples (${pcm.size / 16000f}s)")

                // Step 4: Transcribe via WhisperEngine
                val raw = engine.transcribe(pcm)
                Log.i(TAG, "Raw transcript: '${raw.take(80)}'")

                // Step 5: Filter hallucinations and non-speech markers
                val filtered = HallucinationFilter.filter(raw)
                if (filtered == null) {
                    Log.i(TAG, "Filtered as non-speech")
                    _uiState.value = UiState.NoSpeech("No speech detected")
                } else {
                    Log.i(TAG, "Transcript: '${filtered.take(80)}'")
                    _uiState.value = UiState.Success(filtered)
                }

            } catch (e: CancellationException) {
                throw e // Rethrow so coroutine cancellation propagates
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed: ${e.message}", e)
                _uiState.value = UiState.Error("Transcription failed: ${e.message}")
            }
        }
    }

    /**
     * Cancel the inference job.
     *
     * Note: The whisper.cpp JNI call is blocking and not cooperatively cancellable.
     * The coroutine will be marked cancelled but the native call completes in the
     * background. This is a known MVP limitation (see RESEARCH.md).
     */
    fun cancelInference() {
        inferenceJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()

        // Cancel any running inference
        inferenceJob?.cancel()

        // Delete cached audio file (ownership transferred from ShareReceiverActivity)
        audioFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val deleted = file.delete()
                Log.i(TAG, "Cached audio file deleted: $deleted ($path)")
            }
        }

        // Clean up verification WAV files from Phase 2 debugging
        val audioDir = File(getApplication<Application>().cacheDir, "audio")
        if (audioDir.exists()) {
            audioDir.listFiles()?.filter { it.name.startsWith("verify_") }?.forEach { file ->
                val deleted = file.delete()
                Log.i(TAG, "Verification WAV deleted: $deleted (${file.name})")
            }
        }

        // Engine cleanup: viewModelScope is cancelled in super.onCleared(),
        // so we cannot launch a coroutine to call engine.release().
        // The native memory will be freed when the process exits.
        // This is acceptable for the single-use activity pattern.
        Log.i(TAG, "ViewModel cleared")
    }
}
