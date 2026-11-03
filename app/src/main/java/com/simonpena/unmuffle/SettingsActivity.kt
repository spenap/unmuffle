package com.simonpena.unmuffle

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The transcription language (auto-detect, ask each time, or one) and the licences. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val languageValue = findViewById<TextView>(R.id.language_value)
        languageValue.text = LanguageDialog.label(SettingsRepository.getLanguage(this))
        findViewById<android.view.View>(R.id.language_row).setOnClickListener {
            val choices = listOf(Languages.AUTO, Languages.ASK) + Languages.forPhone()
            val current = choices.indexOf(SettingsRepository.getLanguage(this))
            MaterialAlertDialogBuilder(this)
                .setTitle("Transcription language")
                .setSingleChoiceItems(choices.map(LanguageDialog::label).toTypedArray(), current) { dialog, i ->
                    SettingsRepository.setLanguage(this, choices[i])
                    languageValue.text = LanguageDialog.label(choices[i])
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<android.view.View>(R.id.licences_row).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Licences")
                .setMessage(assets.open("licences.txt").bufferedReader().use { it.readText() })
                .setPositiveButton("Close", null)
                .show()
        }
    }
}
