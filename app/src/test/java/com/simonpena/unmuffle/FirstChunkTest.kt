package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstChunkTest {
    private fun speech(seconds: Float) = FloatArray((seconds * 16000).toInt()) { 0.5f }

    @Test
    fun cutsAtTheQuietestPointNearTenSeconds() {
        val pcm = speech(40f)
        for (i in (11.0 * 16000).toInt() until (11.1 * 16000).toInt()) pcm[i] = 0f
        assertEquals(11.05f, FirstChunk.cut(pcm, "es")!! / 16000f, 0.01f)
    }

    @Test
    fun keepsShortMessagesWhole() {
        assertNull(FirstChunk.cut(speech(15f), "es"))
    }

    @Test
    fun keepsTheWholeWindowForAutoDetect() {
        assertNull(FirstChunk.cut(speech(40f), Languages.AUTO))
    }

    @Test
    fun theWindowFitsTheChunk() {
        assertEquals(564, FirstChunk.audioCtx(10 * 16000))
        assertEquals(0, FirstChunk.audioCtx(40 * 16000))
    }

    @Test
    fun progressCoversBothParts() {
        val cut = 10 * 16000
        val total = 40 * 16000
        assertEquals(13, FirstChunk.progress(first = true, percent = 50, cut, total))
        assertEquals(63, FirstChunk.progress(first = false, percent = 50, cut, total))
        assertEquals(100, FirstChunk.progress(first = false, percent = 100, cut, total))
    }
}
