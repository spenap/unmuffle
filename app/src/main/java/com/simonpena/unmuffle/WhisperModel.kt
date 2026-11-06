package com.simonpena.unmuffle

/** The whisper models the app downloads; tiny is for the instrumented tests. */
enum class WhisperModel(
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    TINY(
        filename = "ggml-tiny-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        sizeBytes = 33_739_776L,
    ),
    SMALL(
        // q8_0, not q5_1: faster on phones and more accurate (docs/measurements.md)
        filename = "ggml-small-q8_0.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
        sha256 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
        sizeBytes = 264_464_607L,
    );

    /** Name and size, as Settings shows it: "Whisper small · 264 MB". */
    fun summary(): String = "Whisper ${name.lowercase()} · ${megabytes(sizeBytes)} MB"
}

/** Whole megabytes in [bytes], decimal, as Android shows file sizes. */
fun megabytes(bytes: Long): Long = maxOf(0L, bytes) / 1_000_000
