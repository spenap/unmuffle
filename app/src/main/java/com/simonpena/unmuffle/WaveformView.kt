package com.simonpena.unmuffle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

/** The voice bubble's waveform: bars played so far in the primary colour. Tap or drag to seek. */
class WaveformView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var bars = FloatArray(0)
        set(value) { field = value; invalidate() }

    /** 0..1 */
    var progress = 0f
        set(value) { field = value; invalidate() }

    var onSeek: (Float) -> Unit = {}

    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@WaveformView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val rest = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@WaveformView, com.google.android.material.R.attr.colorOutlineVariant)
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val step = width / bars.size.toFloat()
        val barWidth = step * 0.6f
        val mid = height / 2f
        for (i in bars.indices) {
            val h = maxOf(barWidth, bars[i] * height)
            val x = i * step + (step - barWidth) / 2
            val paint = if ((i + 0.5f) / bars.size <= progress) played else rest
            canvas.drawRoundRect(x, mid - h / 2, x + barWidth, mid + h / 2, barWidth / 2, barWidth / 2, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val fraction = (event.x / width).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); progress = fraction }
            MotionEvent.ACTION_MOVE -> progress = fraction
            MotionEvent.ACTION_UP -> { onSeek(fraction); performClick() }
            else -> return false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
