package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperModelTest {
    @Test
    fun summaryIsNameAndSize() {
        assertEquals("Whisper small · 264 MB", WhisperModel.SMALL.summary())
    }
}
