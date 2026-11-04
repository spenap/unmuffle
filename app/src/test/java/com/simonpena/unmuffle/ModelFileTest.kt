package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelFileTest {
    @get:Rule val dir = TemporaryFolder()

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theModelIsCopiedAndChecked() {
        val target = dir.root.resolve("model.bin")
        val seen = mutableListOf<Long>()
        assertTrue(ModelFile.copy(bytes.inputStream(), target, sha) { seen += it })
        assertTrue(target.readBytes().contentEquals(bytes))
        assertEquals(bytes.size.toLong(), seen.last())
        assertEquals(seen, seen.sorted())
    }

    @Test
    fun anotherFileIsRefusedAndRemoved() {
        val target = dir.root.resolve("model.bin")
        assertFalse(ModelFile.copy("not a model".byteInputStream(), target, sha) {})
        assertFalse(target.exists())
    }

    @Test
    fun aDownloadAtNothingFor15SecondsHasStalled() {
        assertFalse(ModelFile.stalled(bytes = 0, sinceMs = 14_999))
        assertTrue(ModelFile.stalled(bytes = 0, sinceMs = 15_000))
        assertFalse(ModelFile.stalled(bytes = 1, sinceMs = 60_000))
    }
}
