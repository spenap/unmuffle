package com.simonpena.unmuffle

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/** Where playback is: [position] in seconds, out of [duration]. */
data class PlayerState(val playing: Boolean = false, val position: Float = 0f, val duration: Float = 0f)

/**
 * Plays mono float PCM, the samples already decoded for whisper, so any format
 * the app can transcribe also plays. Call from the main thread. Pauses when
 * another app takes the audio focus.
 */
class PcmPlayer(
    private val samples: FloatArray,
    private val sampleRate: Int,
    private val scope: CoroutineScope,
    private val audioManager: AudioManager,
) {
    private val _state = MutableStateFlow(PlayerState(duration = samples.size / sampleRate.toFloat()))
    val state: StateFlow<PlayerState> = _state

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it < 0) pause() }
        .build()
    private var track: AudioTrack? = null
    private var job: Job? = null
    private var start = 0 // sample the current playback started from
    private var headAtStart = 0

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        if (_state.value.playing || samples.isEmpty()) return
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (start >= samples.size) start = 0
        val t = track ?: newTrack().also { track = it }
        headAtStart = t.playbackHeadPosition
        t.play()
        _state.value = _state.value.copy(playing = true)
        job = scope.launch(Dispatchers.Default) {
            var written = start
            while (isActive) {
                if (written < samples.size) {
                    val n = t.write(samples, written, min(CHUNK, samples.size - written), AudioTrack.WRITE_NON_BLOCKING)
                    if (n > 0) written += n
                }
                val heard = heard(t)
                _state.value = _state.value.copy(position = heard / sampleRate.toFloat())
                if (heard >= samples.size) break
                delay(30)
            }
            if (isActive) launch(Dispatchers.Main) { stop(samples.size) }
        }
    }

    fun pause() {
        val t = track ?: return
        if (!_state.value.playing) return
        stop(heard(t))
    }

    /** Moves to [fraction] of the audio, playing on if it was playing. */
    fun seekTo(fraction: Float) {
        val wasPlaying = _state.value.playing
        pause()
        start = (fraction.coerceIn(0f, 1f) * samples.size).toInt()
        _state.value = _state.value.copy(position = start / sampleRate.toFloat())
        if (wasPlaying) play()
    }

    fun release() {
        job?.cancel()
        track?.release()
        track = null
        audioManager.abandonAudioFocusRequest(focus)
    }

    private fun stop(at: Int) {
        job?.cancel()
        track?.pause()
        track?.flush()
        start = at
        audioManager.abandonAudioFocusRequest(focus)
        _state.value = _state.value.copy(playing = false, position = at / sampleRate.toFloat())
    }

    private fun heard(t: AudioTrack) = min(samples.size, start + (t.playbackHeadPosition - headAtStart))

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, CHUNK * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val CHUNK = 4096
    }
}
