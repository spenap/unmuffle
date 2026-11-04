package com.simonpena.unmuffle

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** The model from a file the user picks, instead of a download. */
object ModelFile {
    /** How long a download may stay at 0 bytes before Home offers a file instead. */
    const val STALL_MS = 15_000L

    private const val REPORT_EVERY = 1 shl 20 // 1 MB

    /**
     * Copies [input] to [target] and checks it against [sha256], reporting
     * the bytes copied. Returns false, leaving nothing at [target], if it
     * isn't the model.
     */
    fun copy(input: InputStream, target: File, sha256: String, onProgress: (Long) -> Unit): Boolean {
        val partial = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var reported = 0L
        input.use { inp ->
            partial.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    copied += n
                    if (copied - reported >= REPORT_EVERY) {
                        reported = copied
                        onProgress(copied)
                    }
                }
            }
        }
        onProgress(copied)
        val ok = digest.digest().joinToString("") { "%02x".format(it) } == sha256
        if (ok) partial.renameTo(target) else partial.delete()
        return ok && target.exists()
    }

    /** Whether a download still at [bytes] after [sinceMs] has not started. */
    fun stalled(bytes: Long, sinceMs: Long): Boolean = bytes == 0L && sinceMs >= STALL_MS
}
