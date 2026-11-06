package com.simonpena.unmuffle

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.text.Collator
import java.util.Locale

/** The transcription language: auto-detect or a whisper code. */
object Languages {
    const val AUTO = "auto"

    /** [supported] codes, the phone's languages first in its order, then the rest by [name], accents aside. */
    fun ordered(supported: List<String>, phone: List<String>, name: (String) -> String): List<String> {
        val first = phone.map { OLD_CODES[it] ?: it }.filter { it in supported }.distinct()
        val collator = Collator.getInstance(Locale.ROOT)
        return first + (supported - first.toSet()).sortedWith(compareBy(collator, name))
    }

    /** The default setting: the phone's first language whisper supports, else auto-detect. */
    fun defaultFor(phone: List<String>): String = phone.firstOrNull() ?: AUTO

    /** The phone's languages to offer for transcribing again, without the one [used]. */
    fun redoOptions(phoneFirst: List<String>, used: String, max: Int = 3): List<String> =
        phoneFirst.filter { it != used }.take(max)

    /** Under a transcript: the language it was transcribed in, a format taking its name. */
    @StringRes
    fun resultLine(detected: Boolean): Int = if (detected) R.string.language_detected else R.string.language_chosen

    /** The phone's languages whisper supports, in the phone's order. */
    fun phone(context: Context): List<String> = phoneCodes(context).map { OLD_CODES[it] ?: it }.filter { it in supported }.distinct()

    /** whisper's languages, as [ordered] for this phone, by their names in [ui]. */
    fun forPhone(context: Context, ui: Locale): List<String> = ordered(supported, phoneCodes(context), { nameIn(it, ui) })

    /** The language's own name, e.g. "Español". */
    fun nativeName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** The language's name in [ui], the app's language: "Spanish", or "Inglés" in Spanish. */
    fun nameIn(code: String, ui: Locale): String =
        Locale.forLanguageTag(code).getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }

    private val supported by lazy { Whisper.languages() }

    // The system's languages, not the app's own (Settings › App languages, Android 13+)
    private fun phoneCodes(context: Context): List<String> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            LocaleList.getDefault()
        }
        return (0 until list.size()).map { list[it].language }
    }

    // Codes Java's Locale may still report for these languages
    private val OLD_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")
}
