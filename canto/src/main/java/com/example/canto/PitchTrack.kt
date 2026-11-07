package com.example.canto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Sequenza delle note (in MIDI frazionario) della voce guida; NaN = nessun suono. */
class PitchTrack(val hopSec: Float, val midi: FloatArray) {

    fun midiAt(timeSec: Float): Float {
        val i = (timeSec / hopSec).roundToInt()
        return if (i in midi.indices) midi[i] else Float.NaN
    }

    /** Nota della voce guida più vicina nel tempo, cercando fino a `radius` frame di distanza. */
    fun nearestVoiced(timeSec: Float, radius: Int): Float {
        val c = (timeSec / hopSec).roundToInt()
        for (d in 0..radius) {
            val a = c - d
            if (a in midi.indices && !midi[a].isNaN()) return midi[a]
            val b = c + d
            if (b in midi.indices && !midi[b].isNaN()) return midi[b]
        }
        return Float.NaN
    }

    /** Intervallo di note da mostrare (5° e 95° percentile, con un po' di margine). */
    fun displayRange(): Pair<Float, Float> {
        val values = midi.filter { !it.isNaN() }.sorted()
        if (values.isEmpty()) return Pair(55f, 70f)
        var lo = floor(values[(values.size * 0.05f).toInt()]) - 2f
        var hi = ceil(values[((values.size - 1) * 0.95f).toInt()]) + 2f
        if (hi - lo < 12f) {
            val mid = (hi + lo) / 2f
            lo = mid - 6f
            hi = mid + 6f
        }
        return Pair(lo, hi)
    }

    fun save(file: File) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeFloat(hopSec)
            out.writeInt(midi.size)
            for (v in midi) out.writeFloat(v)
        }
    }

    companion object {
        fun load(file: File): PitchTrack {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val hop = input.readFloat()
                val n = input.readInt()
                val arr = FloatArray(n)
                for (i in 0 until n) arr[i] = input.readFloat()
                return PitchTrack(hop, arr)
            }
        }
    }
}

object Notes {
    private val names = arrayOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")

    fun name(midi: Float): String {
        val r = midi.roundToInt()
        val n = names[((r % 12) + 12) % 12]
        val octave = Math.floorDiv(r, 12) - 1
        return "$n$octave"
    }

    fun pitchClassName(pc: Int): String = names[((pc % 12) + 12) % 12]
}
