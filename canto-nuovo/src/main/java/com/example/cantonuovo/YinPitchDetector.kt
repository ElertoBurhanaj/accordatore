package com.example.cantonuovo

import kotlin.math.sqrt

/** Stateful YIN detector. Reuses its work buffer so microphone frames do not allocate. */
class YinPitchDetector(
    private val sampleRate: Int,
    frameSize: Int,
    private val minFrequency: Float = 65f,
    private val maxFrequency: Float = 1000f,
    private val threshold: Float = 0.16f
) {
    private val half = frameSize / 2
    private val tauMin = maxOf(2, (sampleRate / maxFrequency).toInt())
    private val tauMax = minOf(half - 1, (sampleRate / minFrequency).toInt())
    private val cumulativeDifference = FloatArray(tauMax + 1)

    init {
        require(sampleRate > 0 && frameSize >= 64 && frameSize % 2 == 0)
        require(tauMax > tauMin + 2) { "La finestra audio è troppo corta per la frequenza richiesta" }
    }

    fun detect(samples: FloatArray, minRms: Float = 0.012f): Float {
        require(samples.size >= half + tauMax) { "Finestra audio troppo corta" }
        var energy = 0.0
        for (sample in samples) energy += sample * sample
        if (sqrt(energy / samples.size) < minRms) return Float.NaN
        cumulativeDifference[0] = 1f
        var runningSum = 0f
        for (tau in 1..tauMax) {
            var difference = 0f
            for (i in 0 until half) {
                val delta = samples[i] - samples[i + tau]
                difference += delta * delta
            }
            runningSum += difference
            cumulativeDifference[tau] =
                if (runningSum > 0f) difference * tau / runningSum else 1f
        }
        var tau = tauMin
        while (tau <= tauMax) {
            if (cumulativeDifference[tau] < threshold) {
                while (tau < tauMax && cumulativeDifference[tau + 1] < cumulativeDifference[tau]) tau++
                if (tau == tauMax) return sampleRate.toFloat() / tau
                val left = cumulativeDifference[tau - 1]
                val center = cumulativeDifference[tau]
                val right = cumulativeDifference[tau + 1]
                val denominator = 2f * (2f * center - left - right)
                val refinedTau = if (denominator == 0f) tau.toFloat()
                else tau + (right - left) / denominator
                return if (refinedTau > 0f) sampleRate / refinedTau else Float.NaN
            }
            tau++
        }
        return Float.NaN
    }
}
