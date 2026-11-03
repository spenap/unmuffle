package com.simonpena.unmuffle

import java.io.ByteArrayOutputStream
import kotlin.math.min

/** A transcribed word and how sure whisper is of it: the lowest probability of its tokens. */
data class Word(val text: String, val p: Float)

/**
 * Parses the bytes from `WhisperLib.takeNewSegments`: each token is its UTF-8
 * text, 0x1F, its probability in ASCII, 0x1E; each segment ends with 0x1D.
 * A token starting with a space starts a word. Grouping works on bytes, since
 * a token can hold part of a character. Punctuation doesn't count towards a
 * word's certainty: whisper is often unsure of a comma, not of the word.
 */
object TokenWords {
    private const val TEXT_END: Byte = 0x1F
    private const val TOKEN_END: Byte = 0x1E
    private const val SEGMENT_END: Byte = 0x1D

    fun parse(bytes: ByteArray): List<List<Word>> {
        val segments = mutableListOf<List<Word>>()
        var words = mutableListOf<Word>()
        val word = ByteArrayOutputStream()
        var p = 1f
        fun endWord() {
            val text = String(word.toByteArray(), Charsets.UTF_8).trim()
            if (text.isNotEmpty()) words += Word(text, p)
            word.reset()
            p = 1f
        }
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == SEGMENT_END) {
                endWord()
                segments += words
                words = mutableListOf()
                i++
                continue
            }
            val textEnd = indexOf(bytes, TEXT_END, i)
            val tokenEnd = indexOf(bytes, TOKEN_END, textEnd)
            if (bytes[i] == ' '.code.toByte()) endWord()
            word.write(bytes, i, textEnd - i)
            if (!isPunctuation(String(bytes, i, textEnd - i, Charsets.UTF_8))) {
                p = min(p, String(bytes, textEnd + 1, tokenEnd - textEnd - 1, Charsets.US_ASCII).toFloat())
            }
            i = tokenEnd + 1
        }
        return segments
    }

    private fun isPunctuation(token: String): Boolean {
        val t = token.trim()
        return t.isNotEmpty() && t.all { !it.isLetterOrDigit() && it != '\uFFFD' }
    }

    private fun indexOf(bytes: ByteArray, b: Byte, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == b) return i
        throw IllegalArgumentException("Truncated token at $from")
    }
}
