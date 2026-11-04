package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioCopierTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun leftoverAudioIsDeleted() {
        val dir = tmp.newFolder("audio")
        dir.resolve("share_1.bin").writeText("a")
        dir.resolve("share_2.bin").writeText("b")
        AudioCopier.clear(dir)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun noFolderIsFine() {
        AudioCopier.clear(tmp.root.resolve("missing"))
    }
}
