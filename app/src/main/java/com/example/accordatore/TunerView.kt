package com.example.accordatore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Indicatore orizzontale da -50 a +50 centesimi con ago colorato. */
class TunerView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A8A99")
        strokeWidth = 2f * density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f * density
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A9BCCB")
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }

    private var cents: Float = 0f
    private var active: Boolean = false

    fun setCents(value: Float) {
        cents = max(-50f, min(50f, value))
        active = true
        invalidate()
    }

    fun clear() {
        active = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 24f * density
        val right = w - 24f * density
        val span = right - left
        val baseY = h * 0.72f

        // Tacche ogni 10 centesimi
        for (c in -50..50 step 10) {
            val x = left + (c + 50) / 100f * span
            val len = if (c == 0) 0f else if (c % 50 == 0) 22f * density else 12f * density
            if (c != 0) canvas.drawLine(x, baseY - len, x, baseY, tickPaint)
        }
        // Linea centrale (intonazione perfetta)
        val cx = left + span / 2f
        canvas.drawLine(cx, baseY - 34f * density, cx, baseY, centerPaint)

        canvas.drawText("-50", left, h - 4f * density, labelPaint)
        canvas.drawText("0", cx, h - 4f * density, labelPaint)
        canvas.drawText("+50", right, h - 4f * density, labelPaint)

        if (active) {
            needlePaint.color = when {
                abs(cents) <= 5f -> Color.parseColor("#4CAF50")
                abs(cents) <= 15f -> Color.parseColor("#E9A23B")
                else -> Color.parseColor("#C0362C")
            }
            val x = left + (cents + 50f) / 100f * span
            canvas.drawLine(x, baseY - 44f * density, x, baseY + 4f * density, needlePaint)
        }
    }
}
