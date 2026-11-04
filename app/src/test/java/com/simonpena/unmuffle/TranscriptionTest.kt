package com.simonpena.unmuffle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionTest {
    /** Answers each call with the next list of segments; records the calls. */
    private class FakeRecognizer(vararg answers: List<String>) : Recognizer {
        val calls = mutableListOf<Pair<Int, String>>() // samples, language
        private val answers = answers.toMutableList()
        override suspend fun transcribe(
            pcm: FloatArray, language: String, audioCtx: Int,
            onProgress: (Int) -> Unit, onSegment: (List<Word>) -> Unit,
        ) {
            calls += pcm.size to language
            for (segment in answers.removeAt(0)) onSegment(segment.split(" ").map { Word(it, 0.9f) })
        }
        override suspend fun language() = calls.last().second.let { if (it == Languages.AUTO) "es" else it }
    }

    private fun speech(seconds: Int) = FloatArray(seconds * 16000) { if (it % 2 == 0) 0.5f else -0.5f }

    private fun run(recognizer: Recognizer, pcm: FloatArray, language: String): List<UiState> {
        val states = mutableListOf<UiState>()
        runBlocking { Transcription(recognizer).run(pcm, language) { states += it } }
        return states
    }

    @Test fun aPreviewThenTheWholeMessage() {
        val fake = FakeRecognizer(listOf("Hola a"), listOf("Hola a todos,", "¿qué tal?"))
        val states = run(fake, speech(40), "es")
        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls[0].first < fake.calls[1].first)
        assertTrue(states.any { it is UiState.Processing && it.partial == "Hola a" })
        assertEquals(UiState.Success("Hola a todos, ¿qué tal?", language = "es"), states.last())
    }

    @Test fun autoDetectHasNoPreview() {
        val fake = FakeRecognizer(listOf("Hola"))
        val last = run(fake, speech(40), Languages.AUTO).last()
        assertEquals(1, fake.calls.size)
        assertEquals(UiState.Success("Hola", language = "es", detected = true), last)
    }

    @Test fun shortMessagesHaveNoPreview() {
        val fake = FakeRecognizer(listOf("Hola"))
        run(fake, speech(10), "es")
        assertEquals(1, fake.calls.size)
    }

    @Test fun markersAloneAreNoSpeech() {
        val last = run(FakeRecognizer(listOf("[BLANK_AUDIO]")), speech(5), "es").last()
        assertTrue(last is UiState.NoSpeech)
    }

    @Test fun thePreviewDoesntStandInForNothing() {
        val last = run(FakeRecognizer(listOf("Hola"), emptyList()), speech(40), "es").last()
        assertTrue(last is UiState.NoSpeech)
    }
}
