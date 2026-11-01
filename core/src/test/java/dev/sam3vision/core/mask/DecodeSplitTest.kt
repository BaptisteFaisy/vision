package dev.sam3vision.core.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sonde de décodage d'une frame : imputation par poste, arrondi en millisecondes, et
 * coût nul quand elle est inactive (`null` : le bloc s'exécute nu, sans lecture d'horloge
 * — il n'y a même pas d'horloge à lire).
 */
class DecodeSplitTest {

    /** Horloge simulée : on avance de [stepNs] à chaque lecture, lectures comptées. */
    private class FakeClock(var now: Long = 0L, val stepNs: Long = 2_000_000L) {
        var reads = 0
        fun read(): Long {
            reads++
            return now.also { now += stepNs }
        }
    }

    @Test
    fun `timed impute la duree au poste demande`() {
        val clock = FakeClock()
        val probe = DecodeProbe(clock::read)

        val result = probe.timed(DecodeStage.B64) { "niveaux" }

        assertEquals("niveaux", result)
        assertEquals(2_000_000L, probe.nanos(DecodeStage.B64))
        assertEquals(0L, probe.nanos(DecodeStage.IMG))
        assertEquals(2, clock.reads)
    }

    @Test
    fun `timed accumule tous les masques de la frame`() {
        val probe = DecodeProbe(FakeClock(stepNs = 500_000L)::read)

        probe.timed(DecodeStage.IMG) { }
        probe.timed(DecodeStage.IMG) { }

        assertEquals(1_000_000L, probe.nanos(DecodeStage.IMG))
    }

    @Test
    fun `une sonde nulle execute nu`() {
        val probe: DecodeProbe? = null

        assertEquals(42, probe.timed(DecodeStage.PAINT) { 40 + 2 })
    }

    @Test
    fun `snapshot arrondit et reset efface`() {
        val probe = DecodeProbe()
        probe.add(DecodeStage.B64, 2_400_000L)
        probe.add(DecodeStage.IMG, 2_500_000L)

        val split = probe.snapshot(totalMs = 9, masks = 3)

        assertEquals(2L, split.b64Ms)
        assertEquals(3L, split.imgMs)
        assertEquals(0L, split.upMs)
        assertEquals(9L, split.totalMs)
        assertEquals(3, split.masks)
        assertTrue(split.known)

        probe.reset()
        assertEquals(0L, probe.nanos(DecodeStage.B64))
        assertEquals(0L, probe.nanos(DecodeStage.IMG))
    }

    @Test
    fun `withTotal complete sans toucher les postes`() {
        val split = DecodeSplit(b64Ms = 2, imgMs = 28, upMs = 1, paintMs = 6, totalMs = -1, masks = 12)

        val completed = split.withTotal(37)

        assertEquals(37L, completed.totalMs)
        assertEquals(2L, completed.b64Ms)
        assertEquals(12, completed.masks)
    }

    @Test
    fun `EMPTY n'a rien a afficher`() {
        assertFalse(DecodeSplit.EMPTY.known)
        assertEquals(-1L, DecodeSplit.EMPTY.totalMs)
        assertEquals(0, DecodeSplit.EMPTY.masks)
    }
}
