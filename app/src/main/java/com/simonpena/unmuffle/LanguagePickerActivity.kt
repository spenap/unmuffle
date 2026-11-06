package com.simonpena.unmuffle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Picks a language: auto-detect (optional), the phone's languages, then all
 * of whisper's, each by its own name, with search. Returns [EXTRA_CODE].
 */
class LanguagePickerActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Language(val code: String, val name: String, val english: String) : Row()
    }

    private lateinit var rows: List<Row>
    private lateinit var adapter: Adapter
    private var selected: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_picker)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        selected = intent.getStringExtra(EXTRA_SELECTED)

        val phone = Languages.phone()
        fun language(code: String) = Row.Language(code, Languages.nativeName(code), Languages.englishName(code))
        val top = if (intent.getBooleanExtra(EXTRA_WITH_AUTO, false)) {
            listOf(Row.Language(Languages.AUTO, getString(R.string.auto_detect), ""))
        } else emptyList()
        val all = Languages.forPhone().drop(phone.size)
        rows = top + Row.Header(getString(R.string.phone_languages)) + phone.map(::language) +
            Row.Header(getString(R.string.all_languages)) + all.map(::language)

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@LanguagePickerActivity)
            adapter = this@LanguagePickerActivity.adapter
        }
        adapter.show(rows)
        findViewById<EditText>(R.id.search).doAfterTextChanged { text ->
            val q = text.toString().trim().lowercase()
            adapter.show(if (q.isEmpty()) rows else rows.filterIsInstance<Row.Language>().filter {
                it.code != Languages.AUTO && (it.name.lowercase().contains(q) || it.english.lowercase().contains(q))
            })
        }
    }

    private fun pick(code: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var shown: List<Row> = emptyList()

        fun show(rows: List<Row>) {
            shown = rows
            notifyDataSetChanged()
        }

        override fun getItemCount() = shown.size
        override fun getItemViewType(position: Int) = if (shown[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.item_language_header else R.layout.item_language
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val row = shown[position]) {
                is Row.Header -> (view as TextView).text = row.title
                is Row.Language -> {
                    view.findViewById<TextView>(R.id.name).text = row.name
                    view.findViewById<TextView>(R.id.english).apply {
                        text = row.english
                        visibility = if (row.english.isEmpty() || row.english == row.name) View.GONE else View.VISIBLE
                    }
                    view.findViewById<View>(R.id.check).visibility =
                        if (row.code == selected) View.VISIBLE else View.INVISIBLE
                    view.setOnClickListener { pick(row.code) }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
        private const val EXTRA_SELECTED = "selected"
        private const val EXTRA_WITH_AUTO = "with_auto"

        fun intent(context: Context, selected: String?, withAuto: Boolean): Intent =
            Intent(context, LanguagePickerActivity::class.java)
                .putExtra(EXTRA_SELECTED, selected)
                .putExtra(EXTRA_WITH_AUTO, withAuto)
    }
}
