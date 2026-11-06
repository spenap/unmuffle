package com.simonpena.unmuffle

import android.content.Context
import android.content.SharedPreferences

/** The settings, in SharedPreferences "unmuffle_prefs". Reads are synchronous; writes don't block. */
object SettingsRepository {

    private const val PREFS_NAME = "unmuffle_prefs"
    private const val KEY_LANGUAGE = "pref_language"
    private const val KEY_MODEL = "pref_model"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The language setting: [Languages.AUTO] or a whisper code; by default the phone's language. */
    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, null) ?: Languages.defaultFor(Languages.phone(context))

    /** Stores the language setting. */
    fun setLanguage(context: Context, languageCode: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /** The model setting; small unless set. */
    fun getModel(context: Context): WhisperModel {
        val name = prefs(context).getString(KEY_MODEL, WhisperModel.SMALL.name)
        return WhisperModel.entries.find { it.name == name } ?: WhisperModel.SMALL
    }

    /** Stores the model setting. */
    fun setModel(context: Context, model: WhisperModel) {
        prefs(context).edit().putString(KEY_MODEL, model.name).apply()
    }
}
