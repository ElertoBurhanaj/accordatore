package com.example.cantonuovo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object AppUi {
    const val BACKGROUND = 0xFF0B1020.toInt()
    const val PANEL = 0xFF151D31.toInt()
    const val TEXT = 0xFFF4F6FC.toInt()
    const val MUTED = 0xFFA3ADC2.toInt()
    const val ACCENT = 0xFF55D6A7.toInt()
    const val WARN = 0xFFF2B45F.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun text(
        context: Context,
        value: CharSequence,
        size: Float = 15f,
        color: Int = TEXT,
        bold: Boolean = false,
        centered: Boolean = false
    ) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (centered) gravity = Gravity.CENTER
        setLineSpacing(dp(context, 3).toFloat(), 1f)
    }

    fun button(context: Context, label: String, onClick: () -> Unit) =
        Button(context).apply {
            text = label
            setTextColor(TEXT)
            setOnClickListener { onClick() }
        }

    fun panel(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        background = GradientDrawable().apply {
            setColor(PANEL)
            cornerRadius = dp(context, 18).toFloat()
        }
    }

    fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
