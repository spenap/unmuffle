package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingTranscriptTest {
    private var now = 0L
    private fun transcript(audioSeconds: Float = 0f) = StreamingTranscript(audioSeconds) { now }

    @Test fun startsIndeterminateAndEmpty() {
        val s = transcript().state
        assertEquals(-1, s.progress)
        assertEquals("", s.partial)
    }

    @Test fun segmentsAppendInOrder() {
        val t = transcript()
        t.segment(" Hola,")
        t.segment(" ¿qué tal?")
        assertEquals("Hola, ¿qué tal?", t.state.partial)
        assertEquals(3, t.state.words)
    }

    @Test fun progressKeepsTheText() {
        val t = transcript()
        t.segment(" Hola")
        assertEquals("Hola", t.progress(40).partial)
    }

    @Test fun segmentKeepsTheProgress() {
        val t = transcript()
        t.progress(40)
        assertEquals(40, t.segment(" Hola").progress)
    }

    @Test fun markersAreNotShown() {
        val t = transcript()
        t.segment(" [BLANK_AUDIO]")
        t.segment(" Hola")
        t.segment(" (music)")
        assertEquals("Hola", t.state.partial)
    }

    @Test fun placeholdersStartFromTheAudioLength() {
        assertEquals(182, transcript(70f).state.placeholders)
    }

    @Test fun placeholdersFollowTheMeasuredRate() {
        val t = transcript(60f)
        t.segment(" " + List(90) { "palabra" }.joinToString(" "))
        assertEquals(90, t.progress(50).placeholders)
    }

    @Test fun placeholdersCoverTheRestWhileNothingIsTranscribed() {
        assertEquals(78, transcript(60f).progress(50).placeholders)
    }

    @Test fun noPlaceholdersAtTheEnd() {
        val t = transcript(60f)
        t.segment(" Hola")
        assertEquals(0, t.progress(100).placeholders)
    }

    @Test fun wordsPerSecondCountsFromTheStart() {
        val t = transcript(60f)
        assertNull(t.state.wordsPerSecond)
        now = 10_000
        t.segment(" " + List(40) { "palabra" }.joinToString(" "))
        assertEquals(4f, t.state.wordsPerSecond!!, 0.001f)
    }

    @Test fun statusLines() {
        assertEquals("Transcribing...", StreamingTranscript.status(UiState.Processing()))
        assertEquals("Transcribing... about 182 words",
            StreamingTranscript.status(UiState.Processing(progress = 0, placeholders = 182)))
        assertEquals("Transcribing... 40 words · 4.0 words/s",
            StreamingTranscript.status(UiState.Processing(progress = 50, words = 40, wordsPerSecond = 4f)))
    }

    @Test fun summaryLine() {
        assertEquals("212 words in 40 s · 5.3 words/s", StreamingTranscript.summary(212, 40.2f))
    }
}
