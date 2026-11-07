package com.example.cantonuovo

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

class VocalTrack(val hopSeconds: Float, val midi: FloatArray) {
    fun nearestVoiced(timeSeconds: Float, radius: Int = 2): Float {
        if (timeSeconds < 0f || midi.isEmpty()) return Float.NaN
        val center = (timeSeconds / hopSeconds).roundToInt()
        for (distance in 0..radius) {
            val before = center - distance
            if (before in midi.indices && midi[before].isFinite()) return midi[before]
            if (distance > 0) {
                val after = center + distance
                if (after in midi.indices && midi[after].isFinite()) return midi[after]
            }
        }
        return Float.NaN
    }

    fun range(): Pair<Float, Float> {
        val values = midi.filter { it.isFinite() }.sorted()
        if (values.isEmpty()) return 48f to 84f
        var low = floor(values[(values.size * 0.05f).toInt()]) - 2f
        var high = ceil(values[((values.size - 1) * 0.95f).toInt()]) + 2f
        if (high - low < 12f) {
            val center = (low + high) / 2f
            low = center - 6f
            high = center + 6f
        }
        return low to high
    }

    fun save(file: File) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            output.writeInt(MAGIC)
            output.writeFloat(hopSeconds)
            output.writeInt(midi.size)
            midi.forEach(output::writeFloat)
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalStateException("Impossibile salvare l'analisi della voce")
        }
    }

    fun cleaned(): VocalTrack {
        val result = midi.copyOf()
        val filtered = midi.copyOf()
        val window = FloatArray(5)
        for (index in midi.indices) {
            if (!midi[index].isFinite()) continue
            var count = 0
            for (neighbor in (index - 2)..(index + 2)) {
                if (neighbor in midi.indices && midi[neighbor].isFinite()) {
                    window[count++] = midi[neighbor]
                }
            }
            if (count >= 3) {
                val sorted = window.copyOf(count).sortedArray()
                filtered[index] = sorted[count / 2]
            }
        }
        var index = 0
        while (index < filtered.size) {
            if (!filtered[index].isFinite()) {
                index++
                continue
            }
            var end = index + 1
            while (end < filtered.size && filtered[end].isFinite()) end++
            if (end - index < SHORT_NOTE_FRAMES) {
                for (frame in index until end) result[frame] = Float.NaN
            } else {
                for (frame in index until end) result[frame] = filtered[frame]
            }
            index = end
        }
        return VocalTrack(hopSeconds, result)
    }

    companion object {
        private const val MAGIC = 0x494E544E
        private const val SHORT_NOTE_FRAMES = 3
        private const val MAX_FRAMES = 2_000_000

        fun load(file: File): VocalTrack =
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == MAGIC) { "File di analisi non valido" }
                val hop = input.readFloat()
                require(hop.isFinite() && hop in 0.005f..0.2f) { "Intervallo di analisi non valido" }
                val count = input.readInt()
                require(count in 1..MAX_FRAMES) { "Analisi vocale non valida" }
                val values = FloatArray(count)
                for (index in values.indices) values[index] = input.readFloat()
                VocalTrack(hop, values)
            }
    }
}
