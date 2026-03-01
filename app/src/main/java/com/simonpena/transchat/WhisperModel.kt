package com.simonpena.transchat

/**
 * Enum representing supported Whisper model variants with their associated metadata.
 *
 * SHA256 values verified from HuggingFace ggerganov/whisper.cpp (2026-03-01).
 * sizeBytes values are used for informational display — ModelManager.isModelReady()
 * uses SHA256 for actual integrity verification, not file size.
 */
enum class WhisperModel(
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val displayName: String
) {
    TINY(
        filename = "ggml-tiny-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        sizeBytes = 33_739_776L,  // approximate — HuggingFace reports 32.2 MB; exact byte count unconfirmed
        displayName = "Tiny (~32 MB, faster)"
    ),
    SMALL(
        filename = "ggml-small-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        sizeBytes = 199_819_264L,  // ~190 MB
        displayName = "Small (~190 MB, more accurate)"
    )
}
