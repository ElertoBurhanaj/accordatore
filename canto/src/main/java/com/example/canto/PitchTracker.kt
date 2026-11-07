package com.example.canto

import kotlin.math.abs

/**
 * Stabilizza le letture grezze del microfono:
 * - ignora i picchi isolati e i salti di ottava momentanei (errori tipici del rilevatore);
 * - accetta un vero cambio di nota solo se si ripete per alcuni frame consecutivi;
 * - leviga leggermente la curva dentro la stessa nota.
 */
class PitchTracker {

    private var stable = Float.NaN
    private var candidate = Float.NaN
    private var candidateCount = 0
    private var silentCount = 0

    fun reset() {
        stable = Float.NaN
        candidate = Float.NaN
        candidateCount = 0
        silentCount = 0
    }

    /** Riceve la nota grezza (MIDI) o NaN se non c'è voce; restituisce la nota stabile o NaN. */
    fun update(raw: Float): Float {
        if (raw.isNaN()) {
            silentCount++
            if (silentCount >= 4) {
                stable = Float.NaN
                candidate = Float.NaN
                candidateCount = 0
            }
            return Float.NaN
        }
        silentCount = 0

        // Inizio di una frase: servono due letture coerenti prima di mostrare qualcosa
        if (stable.isNaN()) {
            if (!candidate.isNaN() && abs(raw - candidate) <= 1.5f) {
                candidateCount++
            } else {
                candidate = raw
                candidateCount = 1
            }
            if (candidateCount >= 2) {
                stable = raw
                candidate = Float.NaN
                candidateCount = 0
                return stable
            }
            return Float.NaN
        }

        val d = raw - stable
        if (abs(d) <= 1.5f) {
            // Variazione normale (vibrato, piccole imprecisioni): leggera media
            stable += 0.5f * d
            candidate = Float.NaN
            candidateCount = 0
            return stable
        }

        // Salto grande: lo accetto solo se persiste (un salto di ottava richiede più conferme)
        if (!candidate.isNaN() && abs(raw - candidate) <= 1.5f) {
            candidateCount++
        } else {
            candidate = raw
            candidateCount = 1
        }
        val octaveJump = abs(abs(d) - 12f) <= 1.5f
        val needed = if (octaveJump) 6 else 3
        if (candidateCount >= needed) {
            stable = raw
            candidate = Float.NaN
            candidateCount = 0
            return stable
        }
        return Float.NaN
    }
}
