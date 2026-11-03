package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HallucinationFilterTest {
    @Test fun blankIsNoSpeech() {
        assertNull(HallucinationFilter.filter("   "))
    }

    @Test fun markersAloneAreNoSpeech() {
        assertNull(HallucinationFilter.filter(" [BLANK_AUDIO] "))
        assertNull(HallucinationFilter.filter("(music) [MUSIC]"))
    }

    @Test fun speechIsTrimmedAndKept() {
        assertEquals("Hola, ¿qué tal?", HallucinationFilter.filter("  Hola, ¿qué tal?\n"))
    }

    @Test fun speechNextToAMarkerIsKept() {
        assertEquals("[MUSIC] Hola a todos", HallucinationFilter.filter("[MUSIC] Hola a todos"))
    }

    @Test fun repetitionIsNoSpeech() {
        assertNull(HallucinationFilter.filter("gracias gracias gracias gracias gracias gracias gracias"))
    }

    @Test fun shortRepetitionIsKept() {
        assertEquals("sí sí sí", HallucinationFilter.filter("sí sí sí"))
    }
}
