package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManagerTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun smallIsQ8() = assertEquals("ggml-small-q8_0.bin", WhisperModel.SMALL.filename)

    @Test
    fun filesOfNoCurrentModelAreObsolete() {
        val old = dir.newFile("ggml-small-q5_1.bin")
        dir.newFile(WhisperModel.SMALL.filename)
        dir.newFile(WhisperModel.TINY.filename)
        assertEquals(listOf(old), ModelManager.obsoleteFiles(dir.root))
    }
}
