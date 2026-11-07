package com.example.canto

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Ricava la curva delle note dalla traccia della sola voce guida. */
object PitchAnalyzer {

    private const val FRAME = 1024
    private const val HOP_SECONDS = 0.02f

    fun analyze(pcm: AudioDecoder.Pcm, onProgress: (Float) -> Unit): PitchTrack {
        val sr = pcm.sampleRate
        val s = pcm.samples
        val hop = max(1, (sr * HOP_SECONDS).toInt())
        val n = if (s.size < FRAME) 0 else (s.size - FRAME) / hop + 1
        if (n == 0) throw IllegalStateException("La traccia vocale è troppo corta")

        // Volume di ogni frame, per scegliere una soglia adatta a questa registrazione
        val rms = FloatArray(n)
        for (i in 0 until n) {
            var e = 0.0
            val start = i * hop
            for (k in 0 until FRAME) e += s[start + k] * s[start + k]
            rms[i] = sqrt(e / FRAME).toFloat()
        }
        val sorted = rms.sortedArray()
        val p90 = sorted[((n - 1) * 0.9f).toInt()]
        val gate = max(0.004f, p90 * 0.1f)

        val buf = FloatArray(FRAME)
        val raw = FloatArray(n) { Float.NaN }
        for (i in 0 until n) {
            if (rms[i] >= gate) {
                System.arraycopy(s, i * hop, buf, 0, FRAME)
                val f = PitchDetector.detect(buf, sr, 70f, 1000f, gate)
                if (f > 0f) raw[i] = (69.0 + 12.0 * ln(f / 440.0) / ln(2.0)).toFloat()
            }
            if (i % 50 == 0) onProgress(i.toFloat() / n)
        }

        // Filtro mediano su 5 frame: elimina i salti isolati (errori di ottava)
        val med = FloatArray(n) { Float.NaN }
        val window = FloatArray(5)
        for (i in 0 until n) {
            if (raw[i].isNaN()) continue
            var c = 0
            for (k in (i - 2)..(i + 2)) {
                if (k in 0 until n && !raw[k].isNaN()) window[c++] = raw[k]
            }
            val part = window.copyOf(c).sortedArray()
            med[i] = part[c / 2]
        }

        // Rimuove i segmenti troppo brevi (meno di 4 frame = 80 ms)
        var i = 0
        while (i < n) {
            if (med[i].isNaN()) {
                i++
                continue
            }
            var j = i
            while (j < n && !med[j].isNaN()) j++
            if (j - i < 4) for (k in i until j) med[k] = Float.NaN
            i = j
        }

        onProgress(1f)
        return PitchTrack(hop.toFloat() / sr, med).cleaned()
    }
}
