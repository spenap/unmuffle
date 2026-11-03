package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperCpuConfigTest {
    @Test
    fun allCoresButTwo() {
        assertEquals(6, WhisperCpuConfig.threadsFor(8))
        assertEquals(2, WhisperCpuConfig.threadsFor(4))
        assertEquals(1, WhisperCpuConfig.threadsFor(2))
        assertEquals(1, WhisperCpuConfig.threadsFor(1))
    }
}
