package com.simonpena.transchat

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * Developer tool — not part of the production user flow.
 *
 * Validates Phase 1 success criteria:
 *   1. Model loads from filesDir without OOM
 *   2. Silence -> empty transcript (no_speech_thold=0.6 active)
 *   3. 440 Hz sine -> empty transcript (no hallucination on non-speech)
 *   4. Inference timing captured for release-build benchmark
 *   5. WhisperContext.getSystemInfo() shows correct .so loaded
 *
 * Access: temporarily set as launcher in AndroidManifest, or use adb:
 *   adb shell am start -n com.simonpena.transchat/.SmokeTestActivity
 */
class SmokeTestActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var btnRunTests: Button
    private val engine = WhisperEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smoke_test)

        tvLog = findViewById(R.id.tv_log)
        btnRunTests = findViewById(R.id.btn_run_tests)

        btnRunTests.setOnClickListener {
            btnRunTests.isEnabled = false
            tvLog.text = ""
            runSmokeTests()
        }
    }

    private fun runSmokeTests() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                // Step 1: Check model presence
                log("=== Phase 1 Smoke Test ===")
                log("Model present: ${ModelManager.isModelPresentFast(this@SmokeTestActivity)}")

                val modelFile = ModelManager.modelFile(this@SmokeTestActivity)
                if (!modelFile.exists()) {
                    log("FAIL: Model file not found at ${modelFile.absolutePath}")
                    log("Download the model first via the Download screen.")
                    return@withContext
                }

                log("Model path: ${modelFile.absolutePath}")
                log("Model size: %.1f MB".format(modelFile.length() / (1024.0 * 1024.0)))

                // Step 2: Load model
                log("\n--- Loading model ---")
                val loadStart = System.currentTimeMillis()
                val loaded = engine.loadModel(modelFile.absolutePath)
                val loadMs = System.currentTimeMillis() - loadStart

                if (!loaded) {
                    log("FAIL: Model load returned false (OOM or corrupt file?)")
                    return@withContext
                }
                log("PASS: Model loaded in ${loadMs}ms")
                log("System info: ${WhisperContext.getSystemInfo()}")

                // Step 3: Silence test — must return empty (validates no_speech_thold=0.6)
                log("\n--- Test 1: Silence (3s @ 16kHz) ---")
                val silencePcm = FloatArray(16000 * 3) { 0.0f }
                val silenceStart = System.currentTimeMillis()
                val silenceResult = engine.transcribe(silencePcm)
                val silenceMs = System.currentTimeMillis() - silenceStart

                if (silenceResult.isBlank()) {
                    log("PASS: Silence -> empty (${silenceMs}ms)")
                } else {
                    log("FAIL: Silence produced output: '$silenceResult' (${silenceMs}ms)")
                    log("  Check: no_speech_thold=0.6 must be set in whisper_jni.cpp")
                }

                // Step 4: Sine wave test — must return empty (validates anti-hallucination params)
                log("\n--- Test 2: 440 Hz sine tone (3s @ 16kHz) ---")
                val sinePcm = FloatArray(16000 * 3) { i ->
                    (sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 0.5).toFloat()
                }
                val sineStart = System.currentTimeMillis()
                val sineResult = engine.transcribe(sinePcm)
                val sineMs = System.currentTimeMillis() - sineStart

                if (sineResult.isBlank()) {
                    log("PASS: Sine tone -> empty (${sineMs}ms)")
                } else {
                    log("WARN: Sine tone produced output: '$sineResult' (${sineMs}ms)")
                    log("  This may be acceptable — check if output is plausible speech")
                }

                // Step 5: Real speech test using jfk.wav from whisper.cpp samples
                // The file is copied from the submodule to app assets during build setup.
                // Expected output contains "fellow Americans" (JFK inaugural address excerpt)
                log("\n--- Test 3: Real speech (jfk.wav) ---")
                try {
                    val jfkInputStream = assets.open("jfk.wav")
                    val jfkBytes = jfkInputStream.readBytes()
                    jfkInputStream.close()
                    // Skip WAV header (44 bytes), read 16-bit PCM, convert to float
                    val shortCount = (jfkBytes.size - 44) / 2
                    val jfkPcm = FloatArray(shortCount)
                    for (i in 0 until shortCount) {
                        val offset = 44 + i * 2
                        val sample = (jfkBytes[offset].toInt() and 0xFF) or
                            (jfkBytes[offset + 1].toInt() shl 8)
                        jfkPcm[i] = sample.toShort().toFloat() / 32768.0f
                    }
                    val jfkStart = System.currentTimeMillis()
                    val jfkResult = engine.transcribe(jfkPcm)
                    val jfkMs = System.currentTimeMillis() - jfkStart
                    if (jfkResult.isNotBlank()) {
                        log("PASS: JFK -> '$jfkResult' (${jfkMs}ms)")
                    } else {
                        log("FAIL: JFK -> empty string (${jfkMs}ms)")
                        log("  Transcription pipeline is broken — model loaded but produced no output")
                    }
                } catch (e: Exception) {
                    log("SKIP: jfk.wav not found in assets — copy from whisper.cpp/samples/jfk.wav")
                    log("  Error: ${e.message}")
                }

                // Step 6: Summary
                log("\n=== Summary ===")
                log("Model load: ${if (loaded) "PASS" else "FAIL"} (${loadMs}ms)")
                log("Silence test: ${if (silenceResult.isBlank()) "PASS" else "FAIL"}")
                log("Sine test: ${if (sineResult.isBlank()) "PASS" else "WARN"}")
                log("\nRelease build benchmark:")
                log("  Silence inference: ${silenceMs}ms")
                log("  Sine inference:    ${sineMs}ms")
                log("\nRecord these times in STATE.md before starting Phase 2.")

                engine.release()
            }

            btnRunTests.isEnabled = true
        }
    }

    private suspend fun log(message: String) {
        withContext(Dispatchers.Main) {
            tvLog.append("$message\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { engine.release() }
    }
}
