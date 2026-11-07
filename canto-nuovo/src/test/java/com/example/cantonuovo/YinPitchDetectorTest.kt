package com.example.cantonuovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class YinPitchDetectorTest {
    @Test
    fun detectsSustainedSingingNotes() {
        val sampleRate = 22050
        val detector = YinPitchDetector(sampleRate, 2048)

        for (expected in listOf(110f, 220f, 440f)) {
            val samples = FloatArray(2048) { index ->
                (0.25 * sin(2.0 * PI * expected * index / sampleRate)).toFloat()
            }
            assertEquals(expected, detector.detect(samples), 1.5f)
        }
    }

    @Test
    fun detectsNotesAtTheLowestSupportedFrequencyWithoutReadingPastTheBuffer() {
        val sampleRate = 22050
        val expected = 65f
        val samples = FloatArray(2048) { index ->
            (0.25 * sin(2.0 * PI * expected * index / sampleRate)).toFloat()
        }

        assertEquals(expected, YinPitchDetector(sampleRate, 2048).detect(samples), 1f)
    }

    @Test
    fun silenceDoesNotProduceAPitch() {
        assertFalse(YinPitchDetector(22050, 2048).detect(FloatArray(2048)).isFinite())
    }

    @Test
    fun mapsFrequencyToItalianNoteNames() {
        assertEquals("La4", Notes.name(Notes.midi(440f)))
        assertEquals("Do4", Notes.name(Notes.midi(261.63f)))
    }

    @Test
    fun stabilizerRejectsAnIsolatedPitchAndTracksARepeatedNote() {
        val stabilizer = PitchStabilizer()

        assertFalse(stabilizer.update(69f).isFinite())
        assertEquals(69f, stabilizer.update(69.1f), 0.2f)
        stabilizer.update(74f)
        assertEquals(74f, stabilizer.update(74f), 0.2f)
    }
}
