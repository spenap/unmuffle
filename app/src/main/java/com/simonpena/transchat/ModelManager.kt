package com.simonpena.transchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private const val TAG = "ModelManager"

/**
 * Manages the Whisper model file stored in app private storage.
 *
 * Model location: context.filesDir/models/ggml-small-q5_1.bin
 * Never stored on external storage — this is private, never backed up or shared.
 *
 * All heavy operations (SHA256 of 190MB file) must be called from a coroutine.
 */
object ModelManager {

    const val MODEL_FILENAME = "ggml-small-q5_1.bin"
    const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"

    // SHA256 retrieved from HuggingFace 2026-02-27 — verify matches downloaded file
    const val MODEL_SHA256 =
        "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"

    const val MODEL_SIZE_BYTES = 199_819_264L  // ~190 MB

    /** Absolute path to the model file in private storage. */
    fun modelFile(context: Context): File {
        val modelsDir = File(context.filesDir, "models")
        modelsDir.mkdirs()  // creates directory if it does not exist; filesDir always exists
        return File(modelsDir, MODEL_FILENAME)
    }

    /**
     * Returns true if the model file exists at the expected path.
     * Does NOT verify the SHA256 — call [isModelReady] for full verification.
     */
    fun modelExists(context: Context): Boolean = modelFile(context).exists()

    /**
     * Full model readiness check: file exists AND SHA256 matches.
     * Runs the SHA256 digest on a 190MB file — call from a coroutine on Dispatchers.IO.
     *
     * @return true if the model is present and has the correct checksum
     */
    suspend fun isModelReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = modelFile(context)
        if (!file.exists()) return@withContext false
        val hash = computeSha256(file)
        val match = hash == MODEL_SHA256
        if (!match) {
            Log.w(TAG, "SHA256 mismatch — expected $MODEL_SHA256, got $hash")
        }
        match
    }

    /**
     * Quick existence-only check (no SHA256). Use in MainActivity routing where
     * we want low latency — the full verify runs after download completes.
     */
    fun isModelPresentFast(context: Context): Boolean = modelFile(context).let {
        it.exists() && it.length() > 0L
    }

    /**
     * Delete the model file (e.g. on failed or corrupt download before retry).
     * Safe to call even if the file does not exist.
     */
    fun deleteModel(context: Context) {
        val file = modelFile(context)
        if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Model file deleted: $deleted")
        }
    }

    /** Compute SHA256 hex digest of [file]. Reads in 8KB chunks. */
    suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(8192).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
