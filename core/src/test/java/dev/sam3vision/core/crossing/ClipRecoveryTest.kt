package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Récupération du feu écrasé : [ClipRecovery] ne doit jamais casser un cas
 * correct, et doit sauver exactement les frames dont le cœur brûlé garde une
 * lampe verte.
 *
 * Pixels synthétiques : le vert écrasé d'une LED réelle est (R 230, G 255,
 * B 230) — saturation 0,10 (donc refusé par [PedLightColor.isLit]) mais
 * projection +0,098. Le rouge écrasé est (255, 220, 215), projection −0,137.
 * Valeurs attendues calculées avec le miroir Python (`clip_recovery.py`) :
 * les deux implémentations doivent rendre les mêmes verdicts.
 */
class ClipRecoveryTest {

    private val background = 0xFF202020.toInt()
    private val green = argb(230, 255, 230)
    private val red = argb(255, 220, 215)

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF shl 24 or (r shl 16) or (g shl 8) or b

    private fun canvas(): IntArray = IntArray(SIDE * SIDE) { background }

    private fun fill(pixels: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, color: Int) {
        for (y in y0..y1) {
            for (x in x0..x1) pixels[y * SIDE + x] = color
        }
    }

    private fun verdict(pixels: IntArray): CrossingState =
        ClipRecovery.verdict(CrossingState.Red, pixels, SIDE, SIDE, 0f)

    @Test
    fun `bonhomme vert ecrase - recupere en vert`() {
        // Barre 8 x 31 en bas du cadre : silhouette de bonhomme, cœur brûlé vert.
        val pixels = canvas()
        fill(pixels, 20, 27, 10, 40, green)
        // Le pipeline de base refuse ce crop : aucun pixel n'est « allumé ».
        assertEquals(CrossingState.Red, PedLightGate.verdict(pixels, SIDE, SIDE))
        val result = ClipRecovery.recover(pixels, SIDE, SIDE, 0f)
        assertTrue(result.recovered)
        assertTrue(result.green >= result.floor)
        assertEquals(CrossingState.Green, verdict(pixels))
    }

    @Test
    fun `bonhomme rouge ecrase - reste rouge`() {
        val pixels = canvas()
        fill(pixels, 20, 27, 10, 40, red)
        val result = ClipRecovery.recover(pixels, SIDE, SIDE, 0f)
        assertFalse(result.recovered)
        assertEquals(0, result.green)
        assertEquals(CrossingState.Red, verdict(pixels))
    }

    @Test
    fun `blanc uniforme - reste rouge (aucun cote)`() {
        val pixels = IntArray(SIDE * SIDE) { 0xFFFFFFFF.toInt() }
        assertFalse(ClipRecovery.recover(pixels, SIDE, SIDE, 0f).recovered)
        assertEquals(CrossingState.Red, verdict(pixels))
    }

    @Test
    fun `plancher de pixels - trois pixels verts ne suffisent pas`() {
        val pixels = canvas()
        fill(pixels, 20, 20, 20, 22, green)
        val result = ClipRecovery.recover(pixels, SIDE, SIDE, 0f)
        assertFalse(result.recovered)
        assertEquals(ClipRecovery.MIN_PIXELS, result.floor)
        assertEquals(CrossingState.Red, verdict(pixels))
    }

    @Test
    fun `disque plein - la garde de forme refuse (feu vehicule)`() {
        // Tache plus large que haute ET remplie : signature d'un disque de face.
        val pixels = canvas()
        fill(pixels, 8, 40, 18, 30, green)
        val result = ClipRecovery.recover(pixels, SIDE, SIDE, 0f)
        assertFalse(result.recovered)
        assertEquals(CrossingState.Red, verdict(pixels))
    }

    @Test
    fun `tache confinee en haut - refusee`() {
        val pixels = canvas()
        fill(pixels, 20, 27, 2, 12, green)
        assertFalse(ClipRecovery.recover(pixels, SIDE, SIDE, 0f).recovered)
        assertEquals(CrossingState.Red, verdict(pixels))
    }

    @Test
    fun `rouge prouve par le chemin normal - la recuperation ne passe pas`() {
        val pixels = canvas()
        fill(pixels, 20, 27, 10, 40, green)
        // Les pixels non écrases prouvent le rouge : priorité absolue.
        assertEquals(CrossingState.Red,
            ClipRecovery.verdict(CrossingState.Red, pixels, SIDE, SIDE, 0.5f))
        assertFalse(ClipRecovery.recover(pixels, SIDE, SIDE, 0.5f).recovered)
    }

    @Test
    fun `vert deja prouve - jamais retouche`() {
        // Sortie du chemin normal : la récupération n'a rien à dire, même sur
        // un crop illisible (aucune lecture de pixels n'est faite).
        assertEquals(CrossingState.Green,
            ClipRecovery.verdict(CrossingState.Green, IntArray(0), 0, 0, 0f))
    }

    @Test
    fun `dimensions incoherentes - refusees`() {
        assertFalse(ClipRecovery.recover(IntArray(10), SIDE, SIDE, 0f).recovered)
        assertFalse(ClipRecovery.recover(IntArray(0), 0, 0, 0f).recovered)
        assertEquals(ClipRecovery.MIN_PIXELS, ClipRecovery.floorPixels(0))
    }

    @Test
    fun `plancher - absolu sur petit crop, relatif sur grand`() {
        assertEquals(ClipRecovery.MIN_PIXELS, ClipRecovery.floorPixels(SIDE * SIDE))
        assertEquals(14, ClipRecovery.floorPixels(96 * 96))
        assertEquals(2_074, ClipRecovery.floorPixels(1_920 * 720))
    }

    private companion object {
        const val SIDE = 48
    }
}
