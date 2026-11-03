package com.simonpena.unmuffle

import android.os.LocaleList
import java.util.Locale

/** The transcription language setting: auto-detect, ask on each share, or a whisper code. */
object Languages {
    const val AUTO = "auto"
    const val ASK = "ask"

    /** The code to transcribe with for [setting], or null to ask. */
    fun codeFor(setting: String): String? = if (setting == ASK) null else setting

    /** [supported] codes, the phone's languages first in its order, then the rest by [name]. */
    fun ordered(supported: List<String>, phone: List<String>, name: (String) -> String): List<String> {
        val first = phone.map { OLD_CODES[it] ?: it }.filter { it in supported }.distinct()
        return first + (supported - first.toSet()).sortedBy(name)
    }

    /** whisper's languages for this phone, as [ordered]. */
    fun forPhone(): List<String> {
        val phone = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].language } }
        return ordered(WhisperContext.languages(), phone, ::displayName)
    }

    /** The language's name in the phone's language, e.g. "español". */
    fun displayName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    // Codes Java's Locale may still report for these languages
    private val OLD_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")
}
