package com.example.canto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
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

    /** Elimina picchi isolati e brevi segmenti lontani dal resto (tipici errori di ottava). */
    fun cleaned(): PitchTrack {
        val n = midi.size
        val out = midi.copyOf()

        fun voicedNear(i: Int, step: Int, maxDist: Int): Float {
            var k = 1
            while (k <= maxDist) {
                val j = i + step * k
                if (j < 0 || j >= n) return Float.NaN
                if (!midi[j].isNaN()) return midi[j]
                k++
            }
            return Float.NaN
        }

        // 1) Frame isolati che contraddicono sia il precedente sia il successivo
        for (i in 0 until n) {
            val v = midi[i]
            if (v.isNaN()) continue
            val p = voicedNear(i, -1, 3)
            val q = voicedNear(i, 1, 3)
            if (!p.isNaN() && !q.isNaN() && abs(p - q) <= 3f && abs(v - p) > 4f && abs(v - q) > 4f) {
                out[i] = Float.NaN
            }
        }

        // 2) Segmenti brevi e lontani dalle note vicine
        var i = 0
        while (i < n) {
            if (out[i].isNaN()) {
                i++
                continue
            }
            var j = i
            var sum = 0f
            while (j < n && !out[j].isNaN()) {
                sum += out[j]
                j++
            }
            val len = j - i
            val mean = sum / len
            if (len < 4) {
                for (k in i until j) out[k] = Float.NaN
            } else if (len < 8) {
                var before = Float.NaN
                var after = Float.NaN
                var k = i - 1
                while (k >= 0 && k >= i - 25) {
                    if (!out[k].isNaN()) {
                        before = out[k]
                        break
                    }
                    k--
                }
                k = j
                while (k < n && k < j + 25) {
                    if (!out[k].isNaN()) {
                        after = out[k]
                        break
                    }
                    k++
                }
                val farFromBefore = before.isNaN() || abs(mean - before) > 9f
                val farFromAfter = after.isNaN() || abs(mean - after) > 9f
                val hasNeighbor = !before.isNaN() || !after.isNaN()
                if (farFromBefore && farFromAfter && (hasNeighbor || len < 6)) {
                    for (m in i until j) out[m] = Float.NaN
                }
            }
            i = j
        }
        return PitchTrack(hopSec, out)
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
