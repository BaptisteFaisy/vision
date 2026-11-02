package dev.sam3vision.core.picto

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tailles d'affichage fixes : même centre, carré, borné à la carte. */
class PictoSizesTest {

    @Test
    fun `le defaut est M, 27 pour cent de la largeur`() {
        assertEquals(PictoSize.M, PictoSize.DEFAULT)
        assertEquals(0.27f, PictoSize.DEFAULT.fraction, 1e-6f)
    }

    @Test
    fun `le panel couvre XS a XL dans l'ordre`() {
        assertEquals(
            listOf(0.14f, 0.20f, 0.27f, 0.35f, 0.45f),
            PictoSize.entries.map { it.fraction },
        )
    }

    @Test
    fun `le carre garde le centre sur carte 9-16`() {
        val hit = PictoHit("id", "keys", 0.10f, 0.20f, 0.50f, 0.05f, 0.9f)
        val fixed = hit.withFixedSize(PictoSize.M, 9f / 16f)
        assertEquals(0.27f, fixed.w, 1e-6f)
        assertEquals(0.27f * 9f / 16f, fixed.h, 1e-6f)
        assertEquals(0.10f + 0.50f / 2f, fixed.x + fixed.w / 2f, 1e-6f)
        assertEquals(0.20f + 0.05f / 2f, fixed.y + fixed.h / 2f, 1e-6f)
        assertEquals("keys", fixed.label)
        assertEquals("id", fixed.pictoId)
    }

    @Test
    fun `le carre borne au bord bas-droit`() {
        val hit = PictoHit("id", "keys", 0.90f, 0.95f, 0.10f, 0.05f, 0.9f)
        val fixed = hit.withFixedSize(PictoSize.M, 9f / 16f)
        assertEquals(1f - fixed.w, fixed.x, 1e-6f)
        assertEquals(1f - fixed.h, fixed.y, 1e-6f)
    }

    @Test
    fun `ratio invalide retombe sur 9-16`() {
        val hit = PictoHit("id", "keys", 0.30f, 0.30f, 0.10f, 0.10f, 0.9f)
        val fixed = hit.withFixedSize(PictoSize.S, 0f)
        assertEquals(0.20f, fixed.w, 1e-6f)
        assertEquals(0.20f * 9f / 16f, fixed.h, 1e-6f)
    }
}
