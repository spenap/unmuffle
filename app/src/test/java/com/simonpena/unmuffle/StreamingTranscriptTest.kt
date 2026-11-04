package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingTranscriptTest {
    private var now = 0L
    private fun transcript(audioSeconds: Float = 0f) = StreamingTranscript(audioSeconds) { now }
    private fun StreamingTranscript.segment(text: String, p: Float = 0.9f) =
        segment(text.trim().split(" ").filter { it.isNotEmpty() }.map { Word(it, p) })

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

    @Test fun aPreviewIsReplaced() {
        val t = transcript()
        t.segment(" Hola", p = 0.1f)
        t.startOver()
        t.segment(" Hola,")
        t.segment(" ¿qué tal?")
        assertEquals("Hola, ¿qué tal?", t.state.partial)
        assertEquals(3, t.state.words)
        assertEquals(emptyList<IntRange>(), t.state.unsure)
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

    @Test fun summaryLine() {
        assertEquals("212 words in 40 s · 5.3 words/s", StreamingTranscript.summary(212, 40.2f))
        assertEquals("212 words in 40 s · 5.3 words/s · 3 unsure", StreamingTranscript.summary(212, 40.2f, 3))
    }

    @Test fun wordsAtTheThresholdAreNotUnsure() {
        val t = transcript()
        t.segment(listOf(Word("sí", StreamingTranscript.UNSURE_BELOW)))
        assertEquals(emptyList<IntRange>(), t.state.unsure)
    }

    @Test fun unsureWordsAreMarkedWhereTheyAre() {
        val t = transcript()
        t.segment(listOf(Word("Hola,", 0.9f), Word("despichada", 0.2f)))
        t.segment(listOf(Word("vale", 0.25f), Word("gracias", 0.95f)))
        assertEquals("Hola, despichada vale gracias", t.state.partial)
        assertEquals(listOf(6 until 16, 17 until 21), t.state.unsure)
    }

    @Test fun markersSpanningWordsAreNotShown() {
        val t = transcript()
        t.segment(listOf(Word("Hola", 0.9f), Word("(speaking", 0.5f), Word("in", 0.5f), Word("Spanish)", 0.5f), Word("adiós", 0.2f)))
        assertEquals("Hola adiós", t.state.partial)
        assertEquals(listOf(5 until 10), t.state.unsure)
    }
}
