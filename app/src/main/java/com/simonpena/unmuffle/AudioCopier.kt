package com.simonpena.unmuffle

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

private const val TAG = "AudioCopier"

/**
 * Copies shared audio to cacheDir/audio/, up to 25 MB. Runs on the main
 * thread (a 25 MB copy takes under 200 ms): the permission to read the URI
 * ends when the sending app leaves.
 */
object AudioCopier {

    const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024 // 25 MB

    sealed class CopyResult {
        data class Success(val file: File) : CopyResult()
        data class TooLarge(val sizeBytes: Long) : CopyResult()
        data object NoContent : CopyResult()
        data object CopyFailed : CopyResult()
    }

    /** The size the provider reports, or -1 if it doesn't (often Telegram). */
    private fun queryFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            cursor.getLong(sizeIndex)
                        } else {
                            -1L
                        }
                    } else {
                        -1L
                    }
                } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "queryFileSize failed for $uri", e)
            -1L
        }
    }

    /** Copies [uri] to cacheDir/audio/share_<time>.bin. */
    fun copyToCache(context: Context, uri: Uri): CopyResult {
        val sizeBytes = queryFileSize(context.contentResolver, uri)

        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            Log.w(TAG, "File too large: $sizeBytes bytes (limit: $MAX_FILE_SIZE_BYTES)")
            return CopyResult.TooLarge(sizeBytes)
        }

        val audioDir = File(context.cacheDir, "audio")
        audioDir.mkdirs()

        val destFile = File(audioDir, "share_${System.currentTimeMillis()}.bin")

        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return CopyResult.NoContent

            val bytesCopied = inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (bytesCopied == 0L) {
                destFile.delete()
                Log.w(TAG, "Zero bytes copied from $uri")
                return CopyResult.NoContent
            }

            if (sizeBytes == -1L && bytesCopied > MAX_FILE_SIZE_BYTES) {
                destFile.delete()
                Log.w(TAG, "Post-copy size exceeds limit: $bytesCopied bytes")
                return CopyResult.TooLarge(bytesCopied)
            }

            Log.i(TAG, "Copied $bytesCopied bytes to ${destFile.absolutePath}")
            CopyResult.Success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed for $uri", e)
            destFile.delete()
            CopyResult.CopyFailed
        }
    }
}
