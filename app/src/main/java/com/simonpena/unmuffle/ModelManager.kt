package com.simonpena.unmuffle

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private const val TAG = "ModelManager"

/**
 * The model files, in the app's external files folder (models/), where
 * DownloadManager can write them; removed on uninstall.
 */
object ModelManager {

    /** [model]'s file. */
    fun modelFile(context: Context, model: WhisperModel): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelsDir = File(baseDir, "models")
        modelsDir.mkdirs()
        return File(modelsDir, model.filename)
    }

    /** The selected model's file. */
    fun modelFile(context: Context): File =
        modelFile(context, SettingsRepository.getModel(context))

    /** Whether [model]'s file is there and its SHA-256 matches. */
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

    /** [isModelReady] for the selected model. */
    suspend fun isModelReady(context: Context): Boolean =
        isModelReady(context, SettingsRepository.getModel(context))

    /** Whether [model]'s file is there, without checking its SHA-256. */
    fun isModelPresentFast(context: Context, model: WhisperModel): Boolean =
        modelFile(context, model).let { it.exists() && it.length() > 0L }

    /** [isModelPresentFast] for the selected model. */
    fun isModelPresentFast(context: Context): Boolean =
        isModelPresentFast(context, SettingsRepository.getModel(context))

    /** Deletes the selected model's file, if there. */
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

    private suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
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
