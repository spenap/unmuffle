package com.simonpena.unmuffle

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
 * Model location: context.getExternalFilesDir(null)/models/<model.filename>
 * Never stored on shared external storage — app-private, deleted on uninstall.
 *
 * All heavy operations (SHA256 of the model) must be called from a coroutine.
 */
object ModelManager {

    /**
     * Absolute path to the model file for [model] in app-private external storage.
     * Uses getExternalFilesDir so DownloadManager (a separate system process) can write to it.
     * Still app-private and deleted on uninstall.
     */
    fun modelFile(context: Context, model: WhisperModel): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelsDir = File(baseDir, "models")
        modelsDir.mkdirs()
        return File(modelsDir, model.filename)
    }

    /**
     * Absolute path to the currently-selected model file.
     * Reads model selection from [SettingsRepository] — defaults to WhisperModel.SMALL.
     */
    fun modelFile(context: Context): File =
        modelFile(context, SettingsRepository.getModel(context))

    /**
     * Returns true if the model file exists at the expected path.
     * Does NOT verify the SHA256 — call [isModelReady] for full verification.
     */
    fun modelExists(context: Context): Boolean = modelFile(context).exists()

    /**
     * Full model readiness check for [model]: file exists AND SHA256 matches.
     * Runs the SHA256 digest on a potentially large file — call from a coroutine on Dispatchers.IO.
     *
     * @return true if the model is present and has the correct checksum
     */
    suspend fun isModelReady(context: Context, model: WhisperModel): Boolean =
        withContext(Dispatchers.IO) {
            val file = modelFile(context, model)
            if (!file.exists()) return@withContext false
            val hash = computeSha256(file)
            val match = hash == model.sha256
            if (!match) {
                Log.w(TAG, "SHA256 mismatch for ${model.name} — expected ${model.sha256}, got $hash")
            }
            match
        }

    /**
     * Full readiness check for the currently-selected model.
     * Reads model selection from [SettingsRepository].
     */
    suspend fun isModelReady(context: Context): Boolean =
        isModelReady(context, SettingsRepository.getModel(context))

    /**
     * Quick existence-only check for [model] (no SHA256). Use where low latency is needed.
     */
    fun isModelPresentFast(context: Context, model: WhisperModel): Boolean =
        modelFile(context, model).let { it.exists() && it.length() > 0L }

    /**
     * Quick existence-only check for the currently-selected model (no SHA256).
     * Reads model selection from [SettingsRepository].
     */
    fun isModelPresentFast(context: Context): Boolean =
        isModelPresentFast(context, SettingsRepository.getModel(context))

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

    /** Files in [dir] that belong to no current model, such as a replaced quantisation. */
    fun obsoleteFiles(dir: File): List<File> {
        val current = WhisperModel.entries.map { it.filename }.toSet()
        return dir.listFiles().orEmpty().filter { it.isFile && it.name !in current }.sortedBy { it.name }
    }

    /** Deletes [obsoleteFiles] from the models folder. */
    fun deleteObsoleteModels(context: Context) {
        for (file in obsoleteFiles(modelFile(context).parentFile!!)) {
            Log.i(TAG, "Deleting obsolete ${file.name}: ${file.delete()}")
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
