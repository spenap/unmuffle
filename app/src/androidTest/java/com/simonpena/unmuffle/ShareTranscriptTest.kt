package com.simonpena.unmuffle

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Sharing jfk.wav ends in its transcript. Run with scripts/device-test.sh. */
@RunWith(AndroidJUnit4::class)
class ShareTranscriptTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var previousModel: WhisperModel
    private lateinit var previousLanguage: String

    @Before
    fun useTinyModel() {
        previousModel = SettingsRepository.getModel(context)
        previousLanguage = SettingsRepository.getLanguage(context)
        SettingsRepository.setModel(context, WhisperModel.TINY)
        SettingsRepository.setLanguage(context, "auto")
        val model = ModelManager.modelFile(context)
        assertTrue("$model is missing; scripts/device-test.sh pushes it", model.length() > 0)
    }

    @After
    fun restoreSettings() {
        SettingsRepository.setModel(context, previousModel)
        SettingsRepository.setLanguage(context, previousLanguage)
    }

    @Test
    fun sharedVoiceMessageIsTranscribed() {
        val wav = File(context.cacheDir, "jfk.wav")
        context.assets.open("jfk.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        val share = Intent(Intent.ACTION_SEND)
            .setClassName(context, ShareReceiverActivity::class.java.name)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(wav))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val monitor = instrumentation.addMonitor(TranscriptionActivity::class.java.name, null, false)

        context.startActivity(share)
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
        assertNotNull("TranscriptionActivity didn't start within 15 s", activity)

        try {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            var outcome: String? = null
            while (outcome == null) {
                if (SystemClock.elapsedRealtime() > deadline) fail("No transcript within 120 s")
                SystemClock.sleep(250)
                instrumentation.runOnMainSync {
                    fun shown(id: Int) = activity.findViewById<View>(id).visibility == View.VISIBLE
                    outcome = when {
                        shown(R.id.error_group) ->
                            "Error: " + activity.findViewById<TextView>(R.id.tv_error).text
                        shown(R.id.no_speech_group) -> "No speech found"
                        shown(R.id.tv_summary) -> ""
                        else -> null
                    }
                }
            }
            assertTrue(outcome!!, outcome!!.isEmpty())
            var transcript = ""
            instrumentation.runOnMainSync {
                transcript = activity.findViewById<TextView>(R.id.tv_text).text.toString()
            }
            assertTrue(
                "Transcript: $transcript",
                transcript.contains("ask not what your country can do for you", ignoreCase = true),
            )
        } finally {
            activity.finish()
            wav.delete()
        }
    }
}
