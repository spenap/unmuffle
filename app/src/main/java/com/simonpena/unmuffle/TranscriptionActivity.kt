package com.simonpena.unmuffle

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
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
 * Under it, the progress, then the language, and the Copy and Share buttons.
 * Opened by [ShareReceiverActivity] with the cached audio's path; the view
 * model survives rotation.
 */
class TranscriptionActivity : AppCompatActivity() {

    private val viewModel: TranscriptionViewModel by viewModels()
    private val placeholderText = PlaceholderText()
    private var twinkle: ValueAnimator? = null
    private val unsureColor by lazy { getColor(R.color.unsure) }

    private val mainGroup by lazy { findViewById<View>(R.id.main_group) }
    private val noSpeechGroup by lazy { findViewById<View>(R.id.no_speech_group) }
    private val errorGroup by lazy { findViewById<View>(R.id.error_group) }
    private val textScroll by lazy { findViewById<ScrollView>(R.id.text_scroll) }
    private val bubble by lazy { findViewById<View>(R.id.bubble) }
    private val bubbleContent by lazy { findViewById<View>(R.id.bubble_content) }
    private val divider by lazy { findViewById<View>(R.id.divider) }
    private val tvText by lazy { findViewById<TextView>(R.id.tv_text) }
    private val tvStatus by lazy { findViewById<TextView>(R.id.tv_status) }
    private val progressBar by lazy { findViewById<LinearProgressIndicator>(R.id.progress_bar) }
    private val languageGroup by lazy { findViewById<View>(R.id.language_group) }
    private val buttons by lazy { findViewById<View>(R.id.buttons) }

    // What the text view shows, to redraw it only when it changes
    private var shownPartial: String? = null
    private var shownPlaceholders = -1
    private var transcript = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcription)
        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH) ?: return finish()
        if (!viewModel.started) viewModel.startTranscription(audioPath, SettingsRepository.getLanguage(this))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.player.filterNotNull().collectLatest { showPlayer(it) } }
                viewModel.uiState.collect { state ->
                    when (state) {
                        is UiState.Processing -> showProcessing(state)
                        is UiState.Success -> showSuccess(state)
                        is UiState.NoSpeech -> showOnly(noSpeechGroup)
                        is UiState.Error -> {
                            showOnly(errorGroup)
                            findViewById<TextView>(R.id.tv_error).text = state.message
                        }
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            viewModel.cancelInference()
            finish()
        }
        findViewById<MaterialButton>(R.id.btn_copy).setOnClickListener { copy() }
        findViewById<MaterialButton>(R.id.btn_share).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, transcript)
            startActivity(Intent.createChooser(send, null))
        }
    }

    override fun onStop() {
        super.onStop()
        stopTwinkle()
        viewModel.player.value?.pause()
    }

    private suspend fun showPlayer(player: PcmPlayer) {
        val btnPlay = findViewById<MaterialButton>(R.id.btn_play)
        val waveform = findViewById<WaveformView>(R.id.waveform)
        val tvTime = findViewById<TextView>(R.id.tv_time)
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

    private fun showProcessing(state: UiState.Processing) {
        showOnly(mainGroup)
        languageGroup.visibility = View.GONE
        buttons.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
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
        if (state.placeholders > 0) startTwinkle() else stopTwinkle()
    }

    private fun showSuccess(state: UiState.Success) {
        showOnly(mainGroup)
        stopTwinkle()
        showLanguage(state)
        // Read from the start, with the player in view.
        if (transcript.isEmpty()) textScroll.post { textScroll.smoothScrollTo(0, 0) }
        transcript = state.transcript
        showText(true)
        tvText.text = PlaceholderText.markUnsure(state.transcript, state.unsure, unsureColor)
        tvText.setTextIsSelectable(true)
        tvStatus.visibility = View.GONE
        progressBar.visibility = View.GONE
        buttons.visibility = View.VISIBLE
    }

    /** The transcript's language, and chips to transcribe again in another. */
    private fun showLanguage(state: UiState.Success) {
        languageGroup.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_language).text =
            Languages.resultLine(Languages.nativeName(state.language), state.detected)
        val chips = findViewById<ChipGroup>(R.id.redo_chips)
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

    private val pickRedoLanguage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(LanguagePickerActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && code != null) viewModel.retranscribe(code)
    }

    private fun showOnly(group: View) {
        for (g in listOf(mainGroup, noSpeechGroup, errorGroup)) g.visibility = if (g == group) View.VISIBLE else View.GONE
        if (group != mainGroup) stopTwinkle()
    }

    private fun showText(hasText: Boolean) {
        divider.visibility = if (hasText) View.VISIBLE else View.GONE
        tvText.visibility = if (hasText) View.VISIBLE else View.GONE
    }

    private fun copy() {
        val clip = ClipData.newPlainText("transcript", transcript)
        // Kept out of the clipboard preview and suggestions
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        // Android 13+ confirms a copy itself
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    /** Placeholders twinkle while the screen is visible, unless animations are off. */
    private fun startTwinkle() {
        if (twinkle != null || !ValueAnimator.areAnimatorsEnabled()) return
        twinkle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                placeholderText.timeMs = SystemClock.uptimeMillis()
                tvText.invalidate()
            }
            start()
        }
    }

    private fun stopTwinkle() {
        twinkle?.cancel()
        twinkle = null
    }

    companion object {
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
    }
}
