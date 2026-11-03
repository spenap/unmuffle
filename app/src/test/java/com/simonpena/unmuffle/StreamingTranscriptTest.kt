package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTranscriptTest {
    @Test fun startsIndeterminateAndEmpty() {
        assertEquals(UiState.Processing(-1, ""), StreamingTranscript().state)
    }

    @Test fun segmentsAppendInOrder() {
        val t = StreamingTranscript()
        t.segment(" Hola,")
        t.segment(" ¿qué tal?")
        assertEquals("Hola, ¿qué tal?", t.state.partial)
    }

    @Test fun progressKeepsTheText() {
        val t = StreamingTranscript()
        t.segment(" Hola")
        assertEquals(UiState.Processing(40, "Hola"), t.progress(40))
    }

    @Test fun segmentKeepsTheProgress() {
        val t = StreamingTranscript()
        t.progress(40)
        assertEquals(UiState.Processing(40, "Hola"), t.segment(" Hola"))
    }

    @Test fun markersAreNotShown() {
        val t = StreamingTranscript()
        t.segment(" [BLANK_AUDIO]")
        t.segment(" Hola")
        t.segment(" (music)")
        assertEquals("Hola", t.state.partial)
    }
}
