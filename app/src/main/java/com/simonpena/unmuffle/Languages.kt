package com.simonpena.unmuffle

import android.os.LocaleList
import java.util.Locale

/** The transcription language: auto-detect or a whisper code. */
object Languages {
    const val AUTO = "auto"

    /** [supported] codes, the phone's languages first in its order, then the rest by [name]. */
    fun ordered(supported: List<String>, phone: List<String>, name: (String) -> String): List<String> {
        val first = phone.map { OLD_CODES[it] ?: it }.filter { it in supported }.distinct()
        return first + (supported - first.toSet()).sortedBy(name)
    }

    /** The default setting: the phone's first language whisper supports, else auto-detect. */
    fun defaultFor(phone: List<String>): String = phone.firstOrNull() ?: AUTO

    /** The phone's languages to offer for transcribing again, without the one [used]. */
    fun redoOptions(phoneFirst: List<String>, used: String, max: Int = 3): List<String> =
        phoneFirst.filter { it != used }.take(max)

    /** Under a transcript: the language it was transcribed in. */
    fun resultLine(name: String, detected: Boolean): String =
        if (detected) "Detected: $name. Not right?" else "Transcribed in $name"

    /** The phone's languages whisper supports, in the phone's order. */
    fun phone(): List<String> = phoneCodes().map { OLD_CODES[it] ?: it }.filter { it in supported }.distinct()

    /** whisper's languages, as [ordered] for this phone. */
    fun forPhone(): List<String> = ordered(supported, phoneCodes(), ::englishName)

    /** The language's own name, e.g. "Español". */
    fun nativeName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** The language's name in English, e.g. "Spanish". */
    fun englishName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)

    private val supported by lazy { Whisper.languages() }

    private fun phoneCodes(): List<String> =
        LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].language } }

    // Codes Java's Locale may still report for these languages
    private val OLD_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")
}
