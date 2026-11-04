package com.simonpena.unmuffle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationFilterTest {
    @Test fun blankIsNoSpeech() {
        assertFalse(HallucinationFilter.isSpeech("   "))
    }

    @Test fun speechIsKept() {
        assertTrue(HallucinationFilter.isSpeech("Hola, ¿qué tal?"))
    }

    @Test fun repetitionIsNoSpeech() {
        assertFalse(HallucinationFilter.isSpeech("gracias gracias gracias gracias gracias gracias gracias"))
    }

    @Test fun shortRepetitionIsKept() {
        assertTrue(HallucinationFilter.isSpeech("sí sí sí"))
    }
}
