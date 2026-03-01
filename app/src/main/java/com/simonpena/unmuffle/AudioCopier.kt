package com.simonpena.unmuffle

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

private const val TAG = "AudioCopier"

/**
 * Stateless utility that copies audio bytes from a content URI to cacheDir/audio/.
 *
 * Runs synchronously on the main thread. A 25 MB copy takes ~50-200 ms,
 * well within the 5 s ANR threshold. Do NOT move to Dispatchers.IO —
 * the content URI permission expires as soon as the caller backgrounds.
 */
object AudioCopier {

    const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024 // 25 MB

    sealed class CopyResult {
        data class Success(val file: File) : CopyResult()
        data class TooLarge(val sizeBytes: Long) : CopyResult()
        data object NoContent : CopyResult()
        data object CopyFailed : CopyResult()
    }

    /**
     * Query the file size from the content provider.
     *
     * @return the size in bytes, or -1L if the size cannot be determined
     *         (cursor null, empty, or SIZE column missing — common with Telegram).
     */
    fun queryFileSize(contentResolver: ContentResolver, uri: Uri): Long {
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

    /**
     * Copy audio bytes from [uri] to cacheDir/audio/share_{timestamp}.bin.
     *
     * Must be called synchronously in onCreate before any coroutine launch —
     * the content URI permission expires when the calling app is backgrounded.
     */
    fun copyToCache(context: Context, uri: Uri): CopyResult {
        val sizeBytes = queryFileSize(context.contentResolver, uri)

        // Reject before copying if size is known and exceeds limit
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
                    input.copyTo(output) // default 8 KB buffer
                }
            }

            if (bytesCopied == 0L) {
                destFile.delete()
                Log.w(TAG, "Zero bytes copied from $uri")
                return CopyResult.NoContent
            }

            // Post-copy size check when provider didn't report size upfront
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
