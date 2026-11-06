package com.simonpena.unmuffle

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagesTest {
    private val names = mapOf("en" to "English", "es" to "Spanish", "gl" to "Galician",
        "pt" to "Portuguese", "de" to "German", "he" to "Hebrew")

    @Test
    fun phoneLanguagesFirstThenByName() {
        val ordered = Languages.ordered(
            supported = listOf("en", "es", "gl", "pt", "de", "he"),
            phone = listOf("gl", "es", "fr", "es"),
            name = { names.getValue(it) },
        )
        assertEquals(listOf("gl", "es", "en", "de", "he", "pt"), ordered)
    }

    @Test
    fun oldJavaCodesMatchWhisper() {
        assertEquals(listOf("he", "en"), Languages.ordered(listOf("en", "he"), listOf("iw"), { names.getValue(it) }))
    }

    @Test
    fun redoOffersThePhoneLanguagesNotUsed() {
        assertEquals(listOf("gl", "es", "en"), Languages.redoOptions(listOf("gl", "es", "en", "de"), used = "pt"))
        assertEquals(listOf("gl", "en"), Languages.redoOptions(listOf("gl", "es", "en"), used = "es"))
    }

    @Test
    fun resultLineSaysWhetherDetected() {
        assertEquals(R.string.language_detected, Languages.resultLine(detected = true))
        assertEquals(R.string.language_chosen, Languages.resultLine(detected = false))
    }

    @Test
    fun defaultIsThePhonesFirstLanguage() {
        assertEquals("gl", Languages.defaultFor(listOf("gl", "es")))
        assertEquals(Languages.AUTO, Languages.defaultFor(emptyList()))
    }
}
