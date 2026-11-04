package com.simonpena.unmuffle

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Transcription screen: the shared voice message as an incoming chat bubble,
 * with a player above and the transcript below, growing as segments arrive.
 * Under it, the progress, then the summary and the Copy and Share buttons.
 *
 * Launched by [ShareReceiverActivity] with the cached audio file path. The
 * ViewModel survives configuration changes, so rotation neither re-runs
 * inference nor stops the player's state.
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

        val mainGroup = findViewById<LinearLayout>(R.id.main_group)
        val noSpeechGroup = findViewById<LinearLayout>(R.id.no_speech_group)
        val errorGroup = findViewById<LinearLayout>(R.id.error_group)
        val textScroll = findViewById<ScrollView>(R.id.text_scroll)
        val bubble = findViewById<View>(R.id.bubble)
        val bubbleContent = findViewById<View>(R.id.bubble_content)
        val btnPlay = findViewById<MaterialButton>(R.id.btn_play)
        val waveform = findViewById<WaveformView>(R.id.waveform)
        val tvTime = findViewById<TextView>(R.id.tv_time)
        val divider = findViewById<View>(R.id.divider)
        val tvText = findViewById<TextView>(R.id.tv_text)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val progressBar = findViewById<LinearProgressIndicator>(R.id.progress_bar)
        val tvSummary = findViewById<TextView>(R.id.tv_summary)
        val buttons = findViewById<View>(R.id.buttons)
        val tvError = findViewById<TextView>(R.id.tv_error)
        val btnCopy = findViewById<MaterialButton>(R.id.btn_copy)
        val btnShare = findViewById<MaterialButton>(R.id.btn_share)

        val audioFilePath = intent.getStringExtra(EXTRA_AUDIO_PATH)
        if (audioFilePath == null) {
            finish()
            return
        }
        if (!viewModel.started) {
            viewModel.startTranscription(audioFilePath, SettingsRepository.getLanguage(this))
        }
        val languageGroup = findViewById<View>(R.id.language_group)
        val tvLanguage = findViewById<TextView>(R.id.tv_language)
        val redoChips = findViewById<ChipGroup>(R.id.redo_chips)

        val unsureColor = getColor(R.color.unsure)
        var shownPartial: String? = null
        var shownPlaceholders = -1
        var transcript = ""

        fun show(group: View) {
            for (g in listOf(mainGroup, noSpeechGroup, errorGroup)) g.visibility = if (g == group) View.VISIBLE else View.GONE
        }

        fun showText(hasText: Boolean) {
            divider.visibility = if (hasText) View.VISIBLE else View.GONE
            tvText.visibility = if (hasText) View.VISIBLE else View.GONE
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.player.filterNotNull().collectLatest { player ->
                        bubble.visibility = View.VISIBLE
                        waveform.bars = viewModel.waveform
                        btnPlay.setOnClickListener { player.toggle() }
                        waveform.onSeek = { player.seekTo(it) }
                        player.state.collect { s ->
                            btnPlay.setIconResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
                            btnPlay.contentDescription = if (s.playing) "Pause" else "Play"
                            waveform.progress = if (s.duration > 0f) s.position / s.duration else 0f
                            tvTime.text = Waveform.time(if (s.playing || s.position > 0f) s.position else s.duration)
                        }
                    }
                }
                viewModel.uiState.collect { state ->
                    when (state) {
                        is UiState.Processing -> {
                            languageGroup.visibility = View.GONE
                            show(mainGroup)
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
                                showText(state.partial.isNotEmpty() || state.placeholders > 0)
                                tvText.text = placeholderText.build(state.partial, state.unsure, unsureColor, state.placeholders)
                            }
                            if (textChanged) textScroll.post {
                                // Keep the end of the real text in the top third.
                                val layout = tvText.layout ?: return@post
                                val line = layout.getLineForOffset(state.partial.length)
                                val y = bubble.top + bubbleContent.top + tvText.top + layout.getLineTop(line)
                                textScroll.smoothScrollTo(0, maxOf(0, y - textScroll.height / 3))
                            }
                            if (state.placeholders > 0) startTwinkle(tvText) else stopTwinkle()
                        }
                        is UiState.Success -> {
                            show(mainGroup)
                            showLanguage(state, languageGroup, tvLanguage, redoChips)
                            // Read from the start, with the player in view.
                            if (transcript.isEmpty()) textScroll.post { textScroll.smoothScrollTo(0, 0) }
                            stopTwinkle()
                            transcript = state.transcript
                            showText(true)
                            tvText.text = PlaceholderText.markUnsure(state.transcript, state.unsure, unsureColor)
                            tvText.setTextIsSelectable(true)
                            tvStatus.visibility = View.GONE
                            progressBar.visibility = View.GONE
                            tvSummary.visibility = View.VISIBLE
                            buttons.visibility = View.VISIBLE
                            tvSummary.text = StreamingTranscript.summary(
                                StreamingTranscript.countWords(state.transcript), state.seconds, state.unsure.size)
                        }
                        is UiState.NoSpeech -> {
                            show(noSpeechGroup)
                            stopTwinkle()
                        }
                        is UiState.Error -> {
                            show(errorGroup)
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

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("transcript", transcript)
            // Kept out of the clipboard preview and suggestions
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setPrimaryClip(clip)
            // Android 13+ shows its own visual confirmation; avoid duplicate toast
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        btnShare.setOnClickListener {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, transcript)
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
        viewModel.player.value?.pause()
    }

    private val pickRedoLanguage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(LanguagePickerActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && code != null) viewModel.retranscribe(code)
    }

    /** The transcript's language, and chips to transcribe again in another. */
    private fun showLanguage(state: UiState.Success, group: View, line: TextView, chips: ChipGroup) {
        group.visibility = View.VISIBLE
        line.text = Languages.resultLine(Languages.nativeName(state.language), state.detected)
        chips.removeAllViews()
        fun chip(label: String, onClick: () -> Unit) = chips.addView(Chip(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
        for (code in Languages.redoOptions(Languages.phone(), state.language)) {
            chip("Redo in ${Languages.nativeName(code)}") { viewModel.retranscribe(code) }
        }
        chip("Other language") {
            pickRedoLanguage.launch(LanguagePickerActivity.intent(this, state.language, withAuto = false))
        }
    }
}
