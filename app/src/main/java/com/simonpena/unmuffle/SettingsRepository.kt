package com.simonpena.unmuffle

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
    private const val KEY_MODEL = "pref_model"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The language setting: [Languages.AUTO] (default), [Languages.ASK] or a whisper code. */
    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, Languages.AUTO) ?: Languages.AUTO

    /** Stores the language setting. Uses .apply() — non-blocking. */
    fun setLanguage(context: Context, languageCode: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
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
