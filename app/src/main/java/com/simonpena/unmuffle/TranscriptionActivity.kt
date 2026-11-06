package com.simonpena.unmuffle

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
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
    private val actions by lazy { findViewById<View>(R.id.actions) }
    private val btnCopy by lazy { findViewById<MaterialButton>(R.id.btn_copy) }

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
                            findViewById<TextView>(R.id.tv_error).text = getString(state.message, state.detail)
                        }
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            viewModel.cancelInference()
            finish()
        }
        btnCopy.setOnClickListener { copy() }
        copyAtRest = btnCopy.iconTint to btnCopy.backgroundTintList
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
            btnPlay.contentDescription = getString(if (s.playing) R.string.pause else R.string.play)
            waveform.progress = if (s.duration > 0f) s.position / s.duration else 0f
            tvTime.text = Waveform.time(if (s.playing || s.position > 0f) s.position else s.duration)
        }
    }

    private fun showProcessing(state: UiState.Processing) {
        showOnly(mainGroup)
        languageGroup.visibility = View.GONE
        actions.visibility = View.GONE
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
        actions.visibility = View.VISIBLE
    }

    /** The transcript's language, and Change to transcribe again in another. */
    private fun showLanguage(state: UiState.Success) {
        languageGroup.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_language).text =
            getString(Languages.resultLine(state.detected), Languages.nativeName(state.language))
        findViewById<View>(R.id.btn_change_language).setOnClickListener { showRedoSheet(state.language) }
    }

    /** The phone's other languages, one tap each, then the full list. */
    private fun showRedoSheet(used: String) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_redo_language, null)
        val options = view.findViewById<LinearLayout>(R.id.redo_options)
        val codes = Languages.redoOptions(Languages.phone(this), used)
        for (code in codes) {
            val row = layoutInflater.inflate(R.layout.item_language, options, false)
            row.findViewById<TextView>(R.id.name).text = Languages.nativeName(code)
            row.findViewById<TextView>(R.id.ui_name).text = Languages.nameIn(code, resources.configuration.locales[0])
            row.findViewById<View>(R.id.check).visibility = View.GONE
            row.setOnClickListener {
                sheet.dismiss()
                viewModel.retranscribe(code)
            }
            options.addView(row)
        }
        if (codes.isEmpty()) view.findViewById<View>(R.id.redo_divider).visibility = View.GONE
        view.findViewById<View>(R.id.redo_more).setOnClickListener {
            sheet.dismiss()
            pickRedoLanguage.launch(LanguagePickerActivity.intent(this, used, withAuto = false))
        }
        sheet.setContentView(view)
        sheet.show()
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
        showCopied(true)
        btnCopy.removeCallbacks(resetCopy)
        btnCopy.postDelayed(resetCopy, 1_500)
    }

    private val resetCopy = Runnable { showCopied(false) }
    private lateinit var copyAtRest: Pair<ColorStateList?, ColorStateList?>

    /** Copy confirms in place: a check on a tonal circle. */
    private fun showCopied(copied: Boolean) {
        val label = getString(if (copied) R.string.copied else R.string.copy)
        btnCopy.contentDescription = label
        btnCopy.tooltipText = label
        btnCopy.setIconResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy)
        val (icon, background) = if (copied) {
            ColorStateList.valueOf(MaterialColors.getColor(btnCopy, MR.attr.colorOnSecondaryContainer)) to
                ColorStateList.valueOf(MaterialColors.getColor(btnCopy, MR.attr.colorSecondaryContainer))
        } else {
            copyAtRest
        }
        btnCopy.iconTint = icon
        btnCopy.backgroundTintList = background
        if (copied) btnCopy.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
