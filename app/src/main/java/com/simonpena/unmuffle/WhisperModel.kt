package com.simonpena.unmuffle

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
        // q8_0, not q5_1: faster on phones and more accurate (docs/measurements.md)
        filename = "ggml-small-q8_0.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
        sha256 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
        sizeBytes = 264_464_607L,  // 252 MB
        displayName = "Small (252 MB, more accurate)"
    )
}
