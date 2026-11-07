package com.example.accordatore

import kotlin.math.sqrt

/**
 * Rilevatore di frequenza fondamentale basato sull'algoritmo YIN.
 * Restituisce la frequenza in Hz oppure -1f se non c'è un suono chiaro.
 */
object PitchDetector {

    private const val THRESHOLD = 0.15f
    private const val MIN_RMS = 0.01f

    fun detect(
        buffer: FloatArray,
        sampleRate: Int,
        minHz: Float = 60f,
        maxHz: Float = 500f
    ): Float {
        val half = buffer.size / 2
        val tauMax = minOf(half - 1, (sampleRate / minHz).toInt())
        val tauMin = maxOf(2, (sampleRate / maxHz).toInt())

        // Gate sul volume: ignora silenzio e rumore di fondo
        var energy = 0.0
        for (i in 0 until buffer.size) energy += buffer[i] * buffer[i]
        val rms = sqrt(energy / buffer.size).toFloat()
        if (rms < MIN_RMS) return -1f

        // Funzione di differenza + differenza cumulativa normalizzata
        val cmnd = FloatArray(tauMax + 1)
        cmnd[0] = 1f
        var runningSum = 0f
        for (tau in 1..tauMax) {
            var sum = 0f
            for (i in 0 until half) {
                val delta = buffer[i] - buffer[i + tau]
                sum += delta * delta
            }
            runningSum += sum
            cmnd[tau] = if (runningSum > 0f) sum * tau / runningSum else 1f
        }

        // Primo minimo sotto soglia
        var tauEstimate = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < THRESHOLD) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                tauEstimate = tau
                break
            }
            tau++
        }
        if (tauEstimate == -1) return -1f

        // Interpolazione parabolica per maggiore precisione
        val x0 = if (tauEstimate > 1) tauEstimate - 1 else tauEstimate
        val x2 = if (tauEstimate < tauMax) tauEstimate + 1 else tauEstimate
        val betterTau: Float = if (x0 == tauEstimate) {
            if (cmnd[tauEstimate] <= cmnd[x2]) tauEstimate.toFloat() else x2.toFloat()
        } else if (x2 == tauEstimate) {
            if (cmnd[tauEstimate] <= cmnd[x0]) tauEstimate.toFloat() else x0.toFloat()
        } else {
            val s0 = cmnd[x0]
            val s1 = cmnd[tauEstimate]
            val s2 = cmnd[x2]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (denom == 0f) tauEstimate.toFloat()
            else tauEstimate + (s2 - s0) / denom
        }

        return sampleRate / betterTau
    }
}
