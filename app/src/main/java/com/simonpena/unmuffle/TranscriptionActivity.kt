package com.simonpena.unmuffle

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/**
 * Transcription progress and result screen.
 *
 * Launched by [ShareReceiverActivity] with the cached audio file path.
 * Observes [TranscriptionViewModel.uiState] and toggles between four
 * view groups: progress (text so far), transcript result, no-speech message,
 * and error message.
 *
 * The ViewModel survives configuration changes (rotation). If the Activity
 * is recreated, the existing state is observed without re-running inference.
 */
class TranscriptionActivity : AppCompatActivity() {

    private val viewModel: TranscriptionViewModel by viewModels()
    private val placeholderText = PlaceholderText()
    private var twinkle: ValueAnimator? = null

    companion object {
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcription)

        // Find views
        val progressGroup = findViewById<LinearLayout>(R.id.progress_group)
        val resultGroup = findViewById<LinearLayout>(R.id.result_group)
        val noSpeechGroup = findViewById<LinearLayout>(R.id.no_speech_group)
        val errorGroup = findViewById<LinearLayout>(R.id.error_group)
        val progressBar = findViewById<LinearProgressIndicator>(R.id.progress_bar)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val partialScroll = findViewById<ScrollView>(R.id.partial_scroll)
        val tvPartial = findViewById<TextView>(R.id.tv_partial)
        val tvTranscript = findViewById<TextView>(R.id.tv_transcript)
        val tvSummary = findViewById<TextView>(R.id.tv_summary)
        val tvError = findViewById<TextView>(R.id.tv_error)
        val btnCopy = findViewById<MaterialButton>(R.id.btn_copy)
        val btnShare = findViewById<MaterialButton>(R.id.btn_share)

        // Extract audio file path from intent
        val audioFilePath = intent.getStringExtra(EXTRA_AUDIO_PATH)
        if (audioFilePath == null) {
            finish()
            return
        }

        // Start transcription only on first creation (not on config change)
        if (savedInstanceState == null) {
            viewModel.startTranscription(audioFilePath)
        }

        var shownPartial: String? = null
        var shownPlaceholders = -1

        // Observe UI state with lifecycle-aware collection
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is UiState.Processing -> {
                            progressGroup.visibility = View.VISIBLE
                            resultGroup.visibility = View.GONE
                            noSpeechGroup.visibility = View.GONE
                            errorGroup.visibility = View.GONE

                            tvStatus.text = StreamingTranscript.status(state)
                            if (state.progress < 0) {
                                progressBar.isIndeterminate = true
                            } else {
                                progressBar.isIndeterminate = false
                                progressBar.setProgressCompat(state.progress, true)
                            }
                            val textChanged = shownPartial != state.partial
                            if (textChanged || shownPlaceholders != state.placeholders) {
                                shownPartial = state.partial
                                shownPlaceholders = state.placeholders
                                tvPartial.text = placeholderText.build(state.partial, state.placeholders)
                            }
                            if (textChanged) partialScroll.post {
                                // Keep the end of the real text in the top third.
                                val layout = tvPartial.layout ?: return@post
                                val line = layout.getLineForOffset(state.partial.length)
                                partialScroll.smoothScrollTo(0, maxOf(0, layout.getLineTop(line) - partialScroll.height / 3))
                            }
                            if (state.placeholders > 0) startTwinkle(tvPartial) else stopTwinkle()
                        }
                        is UiState.Success -> {
                            progressGroup.visibility = View.GONE
                            resultGroup.visibility = View.VISIBLE
                            noSpeechGroup.visibility = View.GONE
                            errorGroup.visibility = View.GONE
                            stopTwinkle()
                            tvTranscript.text = state.transcript
                            tvSummary.text = StreamingTranscript.summary(
                                StreamingTranscript.countWords(state.transcript), state.seconds)
                        }
                        is UiState.NoSpeech -> {
                            progressGroup.visibility = View.GONE
                            resultGroup.visibility = View.GONE
                            noSpeechGroup.visibility = View.VISIBLE
                            errorGroup.visibility = View.GONE
                            stopTwinkle()
                        }
                        is UiState.Error -> {
                            progressGroup.visibility = View.GONE
                            resultGroup.visibility = View.GONE
                            noSpeechGroup.visibility = View.GONE
                            errorGroup.visibility = View.VISIBLE
                            stopTwinkle()
                            tvError.text = state.message
                        }
                    }
                }
            }
        }

        // Back press: cancel inference and return to previous app
        onBackPressedDispatcher.addCallback(this) {
            viewModel.cancelInference()
            finish()
        }

        // Copy button: copy transcript to clipboard
        btnCopy.setOnClickListener {
            val text = tvTranscript.text?.toString() ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("transcript", text)
            clipboard.setPrimaryClip(clip)
            // Android 13+ shows its own visual confirmation; avoid duplicate toast
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Share button: open Android share sheet with transcript text
        btnShare.setOnClickListener {
            val text = tvTranscript.text?.toString() ?: return@setOnClickListener
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(sendIntent, null))
        }
    }

    /** Placeholders twinkle while the screen is visible, unless animations are off. */
    private fun startTwinkle(view: TextView) {
        if (twinkle != null || !ValueAnimator.areAnimatorsEnabled()) return
        twinkle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                placeholderText.timeMs = SystemClock.uptimeMillis()
                view.invalidate()
            }
            start()
        }
    }

    private fun stopTwinkle() {
        twinkle?.cancel()
        twinkle = null
    }

    override fun onStop() {
        super.onStop()
        stopTwinkle()
    }
}
