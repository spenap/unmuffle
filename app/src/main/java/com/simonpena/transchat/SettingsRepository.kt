package com.simonpena.transchat

import android.content.Context
import android.content.SharedPreferences

/**
 * Singleton for reading and writing app preferences via SharedPreferences.
 *
 * Always uses context.applicationContext to avoid activity context leaks.
 * All reads are synchronous — safe to call from Activity.onCreate() without coroutines.
 * All writes use .apply() (async, never .commit()) to avoid blocking the main thread.
 *
 * Preference store: "unmuffle_prefs" (private mode).
 */
object SettingsRepository {

    private const val PREFS_NAME = "unmuffle_prefs"
    private const val KEY_LANGUAGE = "pref_language"
    private const val KEY_LANGUAGE_REMEMBERED = "pref_language_remembered"
    private const val KEY_MODEL = "pref_model"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the stored transcription language code, or "auto" if not set. */
    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "auto") ?: "auto"

    /** Stores the transcription language code. Uses .apply() — non-blocking. */
    fun setLanguage(context: Context, languageCode: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /** Returns true if the user has opted to remember their language choice. Default: false. */
    fun isLanguageRemembered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LANGUAGE_REMEMBERED, false)

    /** Stores the user's preference to remember (or not) the language choice. */
    fun setLanguageRemembered(context: Context, remembered: Boolean) {
        prefs(context).edit().putBoolean(KEY_LANGUAGE_REMEMBERED, remembered).apply()
    }

    /** Returns the stored WhisperModel selection, or WhisperModel.SMALL if not set. */
    fun getModel(context: Context): WhisperModel {
        val name = prefs(context).getString(KEY_MODEL, WhisperModel.SMALL.name)
        return WhisperModel.entries.find { it.name == name } ?: WhisperModel.SMALL
    }

    /** Stores the selected WhisperModel. Uses .apply() — non-blocking. */
    fun setModel(context: Context, model: WhisperModel) {
        prefs(context).edit().putString(KEY_MODEL, model.name).apply()
    }
}
