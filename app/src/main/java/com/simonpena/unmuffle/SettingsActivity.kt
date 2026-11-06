package com.simonpena.unmuffle

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The transcription language, and about: model, licences, version. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var languageValue: TextView

    private val pickLanguage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(LanguagePickerActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && code != null) {
            SettingsRepository.setLanguage(this, code)
            showLanguage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        languageValue = findViewById(R.id.language_value)
        showLanguage()
        findViewById<View>(R.id.language_row).setOnClickListener {
            pickLanguage.launch(LanguagePickerActivity.intent(this, SettingsRepository.getLanguage(this), withAuto = true))
        }

        findViewById<TextView>(R.id.model_value).text = SettingsRepository.getModel(this).summary()
        findViewById<TextView>(R.id.version_value).text =
            packageManager.getPackageInfo(packageName, 0).versionName

        findViewById<View>(R.id.licences_row).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.licences)
                .setMessage(assets.open("licences.txt").bufferedReader().use { it.readText() })
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun showLanguage() {
        val code = SettingsRepository.getLanguage(this)
        languageValue.text = if (code == Languages.AUTO) getString(R.string.auto_detect) else Languages.nativeName(code)
    }
}
