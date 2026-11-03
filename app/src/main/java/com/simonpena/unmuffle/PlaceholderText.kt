package com.simonpena.unmuffle

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The text so far followed by placeholder words: rounded blocks in the text
 * colour that twinkle while [timeMs] advances. Placeholders are numbered from
 * the end, so when fewer remain the first ones go and the rest stay put.
 */
class PlaceholderText {
    /** Animation time; 0 leaves the blocks still. Redraw the TextView after changing it. */
    var timeMs = 0L

    fun build(partial: String, placeholders: Int): CharSequence {
        val sb = SpannableStringBuilder(partial)
        for (k in placeholders - 1 downTo 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            val start = sb.length
            val chars = chars(k)
            repeat(chars) { sb.append('x') }
            sb.setSpan(Block(chars, k), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun chars(k: Int) = 2 + (hash(k).pow(1.6f) * 9).toInt()

    private fun hash(k: Int): Float {
        var x = k * -0x61c88647 + 0x5bd1e995
        x = (x xor (x ushr 15)) * 0x2c1b3c6d
        x = x xor (x ushr 12)
        return (x ushr 8) / 16_777_216f
    }

    /** 0.35..1, a sine with its own period (1.4–3 s) and phase per block. */
    private fun twinkle(k: Int): Float {
        if (timeMs == 0L) return 0.7f
        val period = 1400 + hash(k + 7919) * 1600
        val phase = hash(k + 104_729) * period
        return 0.35f + 0.65f * (0.5f + 0.5f * sin(2 * PI * (timeMs + phase) / period).toFloat())
    }

    private inner class Block(private val chars: Int, private val k: Int) : ReplacementSpan() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return (chars * paint.textSize * 0.5f).toInt()
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val size = paint.textSize
            fill.color = paint.color
            fill.alpha = (paint.alpha * 0.3f * twinkle(k)).toInt()
            val radius = size * 0.3f
            canvas.drawRoundRect(x, y - size * 0.75f, x + chars * size * 0.5f, y + size * 0.15f, radius, radius, fill)
        }
    }
}
