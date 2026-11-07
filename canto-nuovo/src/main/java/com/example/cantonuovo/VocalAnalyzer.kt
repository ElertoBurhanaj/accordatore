package com.example.cantonuovo

import kotlin.math.sqrt

object VocalAnalyzer {
    private const val FRAME_SIZE = 1024
    private const val HOP_MILLISECONDS = 40

    fun analyze(
        pcm: AudioDecoder.Pcm,
        onProgress: (Float) -> Unit
    ): VocalTrack {
        val hop = maxOf(1, pcm.sampleRate * HOP_MILLISECONDS / 1000)
        val frameCount = if (pcm.samples.size < FRAME_SIZE) 0
        else (pcm.samples.size - FRAME_SIZE) / hop + 1
        require(frameCount > 0) { "La traccia vocale deve durare almeno 50 ms" }

        val rms = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * hop
            var energy = 0.0
            for (sample in start until start + FRAME_SIZE) {
                energy += pcm.samples[sample] * pcm.samples[sample]
            }
            rms[frame] = sqrt(energy / FRAME_SIZE).toFloat()
        }
        val percentile90 = rms.sortedArray()[((frameCount - 1) * 0.9f).toInt()]
        val silenceThreshold = maxOf(0.003f, percentile90 * 0.12f)
        val detector = YinPitchDetector(pcm.sampleRate, FRAME_SIZE)
        val frame = FloatArray(FRAME_SIZE)
        val raw = FloatArray(frameCount) { Float.NaN }

        for (index in 0 until frameCount) {
            if (rms[index] >= silenceThreshold) {
                System.arraycopy(pcm.samples, index * hop, frame, 0, FRAME_SIZE)
                val frequency = detector.detect(frame, silenceThreshold)
                if (frequency.isFinite()) raw[index] = Notes.midi(frequency)
            }
            if (index % 25 == 0) onProgress(index.toFloat() / frameCount)
        }
        onProgress(1f)
        return VocalTrack(hop.toFloat() / pcm.sampleRate, raw).cleaned()
    }
}
