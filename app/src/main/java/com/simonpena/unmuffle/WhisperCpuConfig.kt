package com.simonpena.unmuffle

/**
 * Whisper uses all cores but two, which stay free for the UI and the
 * system: fastest on the S25 and close to it on the Pixel 5
 * (docs/measurements.md).
 */
internal object WhisperCpuConfig {
    fun threadsFor(cores: Int): Int = maxOf(1, cores - 2)

    val preferredThreadCount: Int
        get() = threadsFor(Runtime.getRuntime().availableProcessors())
}
