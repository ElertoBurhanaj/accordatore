package com.example.cantonuovo

import kotlin.math.abs

class PitchStabilizer {
    private var stable = Float.NaN
    private var candidate = Float.NaN
    private var candidateFrames = 0
    private var silentFrames = 0

    @Synchronized
    fun reset() {
        stable = Float.NaN
        candidate = Float.NaN
        candidateFrames = 0
        silentFrames = 0
    }

    @Synchronized
    fun update(pitch: Float): Float {
        if (!pitch.isFinite()) {
            silentFrames++
            if (silentFrames >= 4) reset()
            return Float.NaN
        }
        silentFrames = 0

        if (!stable.isFinite() || abs(pitch - stable) > 1.1f) {
            if (candidate.isFinite() && abs(pitch - candidate) < 1f) {
                candidate = (candidate + pitch) / 2f
                candidateFrames++
            } else {
                candidate = pitch
                candidateFrames = 1
            }
            val needed = if (stable.isFinite() && abs(pitch - stable) > 5f) 3 else 2
            if (candidateFrames >= needed) {
                stable = candidate
                candidate = Float.NaN
                candidateFrames = 0
                return stable
            }
            return stable
        }

        stable += 0.4f * (pitch - stable)
        candidate = Float.NaN
        candidateFrames = 0
        return stable
    }
}
