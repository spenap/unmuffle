package com.simonpena.unmuffle

import android.app.Application
import android.media.AudioManager
import android.os.SystemClock
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
private const val WAVEFORM_BARS = 40


/**
 * Loads the model, decodes the shared audio and transcribes it, surviving
 * rotation. Owns the cached audio file and deletes it when cleared.
 */
class TranscriptionViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Processing())
    val uiState: StateFlow<UiState> = _uiState

    private val _player = MutableStateFlow<PcmPlayer?>(null)
    /** Plays the decoded audio; null until it is decoded. */
    val player: StateFlow<PcmPlayer?> = _player

    /** The audio's waveform, set before [player]. */
    var waveform = FloatArray(0)
        private set

    private var whisper: Whisper? = null
    private var inferenceJob: Job? = null
    private var audioFilePath: String? = null

    /** The decoded audio, kept to transcribe again in another language. */
    private var pcm: FloatArray? = null

    /** Whether [startTranscription] ran; survives rotation. */
    var started = false
        private set

    /**
     * Load the model, decode [audioPath] to 16 kHz mono and transcribe it in
     * [languageCode] ("auto" to detect it). Call once.
     */
    fun startTranscription(audioPath: String, languageCode: String) {
        started = true
        audioFilePath = audioPath

        inferenceJob = viewModelScope.launch {
            try {
                val modelPath = ModelManager.modelFile(getApplication()).absolutePath
                whisper = Whisper.load(modelPath)
                if (whisper == null) {
                    _uiState.value = UiState.Error("Could not load model")
                    return@launch
                }

                val decoded = withContext(Dispatchers.Default) { AudioDecoder.decode(audioPath) }
                if (decoded == null) {
                    _uiState.value = UiState.Error("Could not decode audio")
                    return@launch
                }
                Log.i(TAG, "Decoded: ${decoded.samples.size} samples, ${decoded.sampleRate}Hz, ${decoded.channels}ch")

                val pcm = withContext(Dispatchers.Default) { PcmResampler.toWhisperFormat(decoded) }
                if (pcm == null) {
                    _uiState.value = UiState.Error("Could not resample audio")
                    return@launch
                }
                Log.i(TAG, "Resampled: ${pcm.size} samples (${pcm.size / 16000f}s)")
                this@TranscriptionViewModel.pcm = pcm
                waveform = Waveform.bars(pcm, WAVEFORM_BARS)
                _player.value = PcmPlayer(
                    pcm, 16000, viewModelScope,
                    getApplication<Application>().getSystemService(AudioManager::class.java),
                )
                transcribe(pcm, languageCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed: ${e.message}", e)
                _uiState.value = UiState.Error("Transcription failed: ${e.message}")
            }
        }
    }

    /** Transcribes the same audio again in [languageCode]. */
    fun retranscribe(languageCode: String) {
        val pcm = pcm ?: return
        if (inferenceJob?.isActive == true) return
        inferenceJob = viewModelScope.launch {
            try {
                transcribe(pcm, languageCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed: ${e.message}", e)
                _uiState.value = UiState.Error("Transcription failed: ${e.message}")
            }
        }
    }

    private suspend fun transcribe(pcm: FloatArray, languageCode: String) {
        val whisper = whisper ?: return
        Transcription(whisper).run(pcm, languageCode) { _uiState.value = it }
    }

    /** Stops waiting for the transcription; whisper itself runs to the end in the background. */
    fun cancelInference() {
        inferenceJob?.cancel()
    }

    override fun onCleared() {
        _player.value?.release()
        super.onCleared()
        inferenceJob?.cancel()
        whisper?.close()
        audioFilePath?.let { Log.i(TAG, "Cached audio deleted: ${File(it).delete()}") }
    }
}
