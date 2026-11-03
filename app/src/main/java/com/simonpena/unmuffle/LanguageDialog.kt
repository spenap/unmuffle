package com.simonpena.unmuffle

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Language labels and the per-message language choice ("Ask each time"). */
object LanguageDialog {
    fun label(setting: String): String = when (setting) {
        Languages.AUTO -> "Auto-detect"
        Languages.ASK -> "Ask each time"
        else -> Languages.displayName(setting)
    }

    /** Asks the language of this message; [onChosen] gets a whisper code or "auto". */
    fun ask(activity: Activity, onCancel: () -> Unit, onChosen: (String) -> Unit) {
        val codes = listOf(Languages.AUTO) + Languages.forPhone()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Language of this message")
            .setItems(codes.map(::label).toTypedArray()) { _, i -> onChosen(codes[i]) }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
