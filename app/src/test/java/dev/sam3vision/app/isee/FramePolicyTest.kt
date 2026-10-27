package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille la politique de frame de la page caméra : **une seule taille** demandée au
 * capteur et envoyée à SAM, jamais agrandie, et une qualité JPEG dans une plage utile.
 *
 * Ces tests existent pour une raison précise : le 2026-10-26 la conversion coûtait
 * 12-42 ms par frame parce que la taille *demandée* (512) et la taille *envoyée* (448)
 * avaient divergé, et que la rotation se faisait sur le buffer plein.
 */
class FramePolicyTest {

    @Test
    fun `le cote envoye est borne par la politique, jamais agrandi`() {
        // Un capteur plus grand que la politique est réduit à EDGE…
        assertEquals(320, FramePolicy.sentEdge(640, 480))
        assertEquals(320, FramePolicy.sentEdge(1920, 1080))
        // …un buffer déjà à l'échelle ne bouge pas…
        assertEquals(320, FramePolicy.sentEdge(320, 240))
        // …et un buffer plus petit n'est jamais étiré.
        assertEquals(160, FramePolicy.sentEdge(160, 120))
    }

    @Test
    fun `la taille demandee au capteur est celle qu_on envoie`() {
        assertEquals(320 to 240, FramePolicy.requestedSize(640, 480))
        assertEquals(320 to 180, FramePolicy.requestedSize(1920, 1080))
        assertEquals(180 to 320, FramePolicy.requestedSize(1080, 1920))
        assertEquals(
            FramePolicy.EDGE,
            maxOf(FramePolicy.requestedSize(640, 480).first, FramePolicy.requestedSize(640, 480).second),
        )
    }

    @Test
    fun `la regle de repli prefere plus petit pour ne pas payer de reduction`() {
        assertEquals(FramePolicy.Fallback.LOWER_THEN_HIGHER, FramePolicy.FALLBACK)
    }

    @Test
    fun `un buffer a l_echelle n_est pas redimensionne`() {
        assertTrue(FramePolicy.isNativeScale(320, 240))
        assertFalse(FramePolicy.isNativeScale(640, 480))
    }

    @Test
    fun `la qualite jpeg reste dans la plage utile`() {
        assertTrue("qualité trop basse : ${FramePolicy.QUALITY}", FramePolicy.QUALITY >= 40)
        assertTrue("qualité inutilement haute : ${FramePolicy.QUALITY}", FramePolicy.QUALITY <= 80)
    }

    @Test
    fun `une taille de capteur absurde ne fait pas planter le calcul`() {
        assertEquals(0 to 0, FramePolicy.requestedSize(0, 0))
        assertEquals(1 to FramePolicy.EDGE, FramePolicy.requestedSize(0, 4800))
    }
}