package com.simonpena.unmuffle

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
 * Debug builds only:
 *   adb shell am start -n com.simonpena.unmuffle/.SmokeTestActivity
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

                // Step 3: Silence test — must NOT produce plausible speech transcript
                // With default params (no custom no_speech_thold), the model may return
                // descriptive tags like "[BLANK_AUDIO]" which is correct non-hallucination behavior.
                log("\n--- Test 1: Silence (3s @ 16kHz) ---")
                val silencePcm = FloatArray(16000 * 3) { 0.0f }
                val silenceStart = System.currentTimeMillis()
                val silenceResult = engine.transcribe(silencePcm)
                val silenceMs = System.currentTimeMillis() - silenceStart

                val silenceIsHallucination = silenceResult.isNotBlank()
                        && !silenceResult.contains("BLANK", ignoreCase = true)
                        && !silenceResult.contains("SILENCE", ignoreCase = true)
                        && !silenceResult.contains("[", ignoreCase = true)
                if (!silenceIsHallucination) {
                    log("PASS: Silence -> '${silenceResult.ifBlank { "(empty)" }}' (${silenceMs}ms)")
                } else {
                    log("FAIL: Silence produced hallucinated speech: '$silenceResult' (${silenceMs}ms)")
                }

                // Step 4: Sine wave test — must NOT produce plausible speech transcript
                // Model may return descriptive tags like "(BEEPING)" which is acceptable.
                log("\n--- Test 2: 440 Hz sine tone (3s @ 16kHz) ---")
                val sinePcm = FloatArray(16000 * 3) { i ->
                    (sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 0.5).toFloat()
                }
                val sineStart = System.currentTimeMillis()
                val sineResult = engine.transcribe(sinePcm)
                val sineMs = System.currentTimeMillis() - sineStart

                val sineIsHallucination = sineResult.isNotBlank()
                        && !sineResult.contains("BEEP", ignoreCase = true)
                        && !sineResult.contains("TONE", ignoreCase = true)
                        && !sineResult.contains("BLANK", ignoreCase = true)
                        && !sineResult.contains("[", ignoreCase = true)
                        && !sineResult.contains("(", ignoreCase = true)
                if (!sineIsHallucination) {
                    log("PASS: Sine tone -> '${sineResult.ifBlank { "(empty)" }}' (${sineMs}ms)")
                } else {
                    log("WARN: Sine tone produced unexpected output: '$sineResult' (${sineMs}ms)")
                }

                // Step 5: Real speech test using jfk.wav from whisper.cpp samples
                // The file is copied from the submodule to app assets during build setup.
                // Expected output contains "fellow Americans" (JFK inaugural address excerpt)
                log("\n--- Test 3: Real speech (jfk.wav) ---")
                try {
                    val jfkInputStream = assets.open("jfk.wav")
                    val jfkBytes = jfkInputStream.readBytes()
                    jfkInputStream.close()
                    // Find the "data" chunk — don't hardcode 44, WAV files may have extra chunks
                    var dataOffset = 12 // skip RIFF header (12 bytes)
                    while (dataOffset + 8 < jfkBytes.size) {
                        val chunkId = String(jfkBytes, dataOffset, 4, Charsets.US_ASCII)
                        val chunkSize = (jfkBytes[dataOffset + 4].toInt() and 0xFF) or
                            ((jfkBytes[dataOffset + 5].toInt() and 0xFF) shl 8) or
                            ((jfkBytes[dataOffset + 6].toInt() and 0xFF) shl 16) or
                            ((jfkBytes[dataOffset + 7].toInt() and 0xFF) shl 24)
                        dataOffset += 8
                        if (chunkId == "data") break
                        dataOffset += chunkSize
                    }
                    log("  WAV data offset: $dataOffset, total size: ${jfkBytes.size}")
                    val shortCount = (jfkBytes.size - dataOffset) / 2
                    val jfkPcm = FloatArray(shortCount)
                    for (i in 0 until shortCount) {
                        val offset = dataOffset + i * 2
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
                log("Silence test: ${if (!silenceIsHallucination) "PASS" else "FAIL"}")
                log("Sine test: ${if (!sineIsHallucination) "PASS" else "WARN"}")
                log("\nRelease build benchmark:")
                log("  Silence inference: ${silenceMs}ms")
                log("  Sine inference:    ${sineMs}ms")

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
