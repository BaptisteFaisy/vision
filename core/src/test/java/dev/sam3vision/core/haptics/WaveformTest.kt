package dev.sam3vision.core.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {

    @Test
    fun `l'appui long commence par la salve puis boucle la nappe`() {
        val wave = HapticPattern.holdLoop()

        // La salve historique est intacte : deux temps forts séparés d'un creux.
        assertEquals(listOf(0L, 70L, 90L, 70L), wave.timings.take(4))
        assertEquals(listOf(0, 255, 0, 255), wave.amplitudes.take(4))
        // Puis un creux, et la nappe bouclée depuis son premier temps fort.
        assertEquals(0, wave.amplitudes[4])
        assertEquals(HapticPattern.LOOP_MS, wave.timings[5])
        assertEquals(5, wave.repeatIndex)
        assertTrue(wave.looped)
    }

    @Test
    fun `chaque timing de l'appui long porte son amplitude`() {
        val wave = HapticPattern.holdLoop()

        assertEquals(wave.timings.size, wave.amplitudes.size)
        // Le creux porte son 0 : sans lui, le moteur refuse le motif d'un bloc.
        assertEquals(0, wave.amplitudes[2])
        assertEquals(0, wave.amplitudes[4])
        assertEquals(0, wave.amplitudes.last())
        assertEquals(HapticPattern.LIGHT_AMPLITUDE, wave.amplitudes[5])
    }

    @Test
    fun `la queue du relachement est une salve unique de 200 ms`() {
        val wave = HapticPattern.releaseTail()

        assertEquals(listOf(HapticPattern.TAIL_MS), wave.timings)
        assertEquals(listOf(HapticPattern.TAIL_AMPLITUDE), wave.amplitudes)
        assertFalse(wave.looped)
        assertEquals(Waveform.NO_REPEAT, wave.repeatIndex)
    }

    @Test
    fun `le creneau du balayage est repeté depuis son premier temps fort`() {
        val wave = HapticPattern.swipeCrest()

        assertEquals(listOf(0L, 30L, 60L), wave.timings)
        assertEquals(listOf(0, 96, 0), wave.amplitudes)
        assertEquals(1, wave.repeatIndex)
        assertTrue(wave.looped)
    }

    @Test
    fun `un moteur sans controle de force utilise l'amplitude par defaut`() {
        val wave = HapticPattern.holdLoop(salveAmplitude = Waveform.DEFAULT_AMPLITUDE, loopAmplitude = -1)

        assertEquals(listOf(0, -1, 0, -1, 0, -1, 0), wave.amplitudes)
        assertEquals(7, wave.timings.size)
    }

    @Test
    fun `les tableaux rendus sont ceux attendus par VibrationEffect`() {
        val wave = HapticPattern.swipeCrest(onMs = 120, offMs = 40)

        assertEquals(listOf(0L, 120L, 40L), wave.timingsArray().toList())
        assertEquals(listOf(0, 96, 0), wave.amplitudesArray().toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `des tailles differentes sont refusees`() {
        Waveform(listOf(0L, 10L), listOf(0), 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un index de bouclage hors bornes est refuse`() {
        Waveform(listOf(0L, 10L), listOf(0, 80), 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `une amplitude hors bornes est refusee`() {
        Waveform(listOf(0L, 10L), listOf(0, 400), 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un motif vide est refuse`() {
        Waveform(emptyList(), emptyList())
    }
}