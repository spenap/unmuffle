package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class TokenWordsTest {
    private val out = ByteArrayOutputStream()
    private fun token(bytes: ByteArray, p: Float) = apply {
        out.write(bytes); out.write(0x1F); out.write(p.toString().toByteArray()); out.write(0x1E)
    }
    private fun token(text: String, p: Float) = token(text.toByteArray(), p)
    private fun endSegment() = apply { out.write(0x1D) }

    @Test fun tokensJoinIntoWordsAtSpaces() {
        token(" Ho", 0.9f).token("la", 0.8f).token(",", 0.95f).token(" qué", 0.7f).token(" tal", 0.99f).endSegment()
        assertEquals(
            listOf(listOf(Word("Hola,", 0.8f), Word("qué", 0.7f), Word("tal", 0.99f))),
            TokenWords.parse(out.toByteArray()),
        )
    }

    @Test fun aCharacterSplitAcrossTokensStaysWhole() {
        val ñ = "ñ".toByteArray()
        token(" ni", 0.9f).token(byteArrayOf(ñ[0]), 0.4f).token(byteArrayOf(ñ[1]) + "o".toByteArray(), 0.6f).endSegment()
        assertEquals(listOf(listOf(Word("niño", 0.4f))), TokenWords.parse(out.toByteArray()))
    }

    @Test fun segmentsStaySeparate() {
        token(" Uno", 0.9f).endSegment().token(" Dos", 0.8f).endSegment()
        assertEquals(listOf(listOf(Word("Uno", 0.9f)), listOf(Word("Dos", 0.8f))), TokenWords.parse(out.toByteArray()))
    }

    @Test fun nothingTaken() {
        assertEquals(emptyList<List<Word>>(), TokenWords.parse(ByteArray(0)))
    }

    @Test fun punctuationDoesNotLowerAWord() {
        token(" vale", 0.9f).token(",", 0.2f).token(" ¿", 0.3f).token("qué", 0.8f).token("?", 0.1f).endSegment()
        assertEquals(listOf(listOf(Word("vale,", 0.9f), Word("¿qué?", 0.8f))), TokenWords.parse(out.toByteArray()))
    }
}
