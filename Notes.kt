package com.example.cantonuovo

import kotlin.math.ln
import kotlin.math.roundToInt

object Notes {
    private val names = arrayOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")

    fun midi(frequency: Float): Float =
        if (frequency > 0f) (69.0 + 12.0 * ln(frequency / 440.0) / ln(2.0)).toFloat() else Float.NaN

    fun name(midi: Float): String {
        if (!midi.isFinite()) return "–"
        val note = midi.roundToInt()
        val octave = Math.floorDiv(note, 12) - 1
        return "${names[Math.floorMod(note, 12)]}$octave"
    }

    fun cents(midi: Float): Int = ((midi - midi.roundToInt()) * 100).roundToInt()
}
