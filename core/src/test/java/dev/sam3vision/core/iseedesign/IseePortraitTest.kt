package dev.sam3vision.core.iseedesign

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IseePortraitTest {
    @Test
    fun `boule au repos centree a 40 pour cent, rayon un tiers de la largeur`() {
        val g = portraitGeometry(1080f, 2400f, 0f)
        assertEquals(540f, g.orbX, 0.01f)
        assertEquals(960f, g.orbY, 0.01f)
        assertEquals(361.8f, g.orbR, 0.1f)
        assertEquals(0f, g.pictureH, 0.01f)
    }

    @Test
    fun `camera ouverte, la boule se pose sur la jointure de la rue`() {
        val g = portraitGeometry(1080f, 2400f, 1f)
        assertEquals(2400f * 0.58f, g.pictureH, 0.1f)
        assertEquals(2400f * 0.555f, g.orbY, 0.1f)
        // La jointure (bas de la rue) passe dans la boule.
        assertTrue(g.orbY - g.orbR < g.pictureH && g.pictureH < g.orbY + g.orbR)
    }

    @Test
    fun `la phrase est toujours sous la boule`() {
        for (k in listOf(0f, 0.3f, 0.7f, 1f)) {
            val g = portraitGeometry(1080f, 2400f, k)
            assertTrue("open=$k", g.textTop > g.orbY + g.orbR)
        }
    }

    @Test
    fun `la rue est pleine en haut, se fond en bas, nulle dessous`() {
        val h = 1392f
        assertEquals(1f, pictureAlpha(10f, h), 1e-4f)
        assertEquals(1f, pictureAlpha(h * 0.5f, h), 1e-4f)
        val mid = pictureAlpha(h * (1f - IseePortrait.FADE / 2f), h)
        assertTrue(mid > 0.2f && mid < 0.8f)
        assertEquals(0f, pictureAlpha(h + 5f, h), 0f)
        assertEquals(0f, pictureAlpha(10f, 0f), 0f)
    }

    @Test
    fun `la mise au point part floue et agrandie, finit nette`() {
        val start = focusPull(0f)
        val end = focusPull(1f)
        assertEquals(24f, start.blur, 0.01f)
        assertEquals(1.05f, start.scale, 1e-4f)
        assertEquals(0f, end.blur, 0.01f)
        assertEquals(1f, end.scale, 1e-4f)
    }

    @Test
    fun `l'enveloppe de voix reste dans 0-1 et bouge`() {
        val v = (0 until 200).map { voiceEnvelope(it * 0.02f) }
        assertTrue(v.all { it in 0f..1f })
        assertTrue(v.max() - v.min() > 0.4f)
    }

    @Test
    fun `les mots s'allument un a un`() {
        val s = "Is it safe to cross?"
        assertEquals(0, wordsSpoken(s, 0, 2000))
        assertEquals(1, wordsSpoken(s, 200, 2000))
        assertEquals(5, wordsSpoken(s, 2000, 2000))
        assertEquals(0, wordsSpoken("", 900, 2000))
    }

    @Test
    fun `la palette Amber est figee, bleu user et ambre iSee`() {
        assertEquals(0xFF0B5FFF.toInt(), IseePortrait.YOU)
        assertEquals(0xFF0A50D8.toInt(), IseePortrait.YOU_INK)
        assertEquals(0xFFF5A300.toInt(), IseePortrait.SEE)
        assertEquals(0xFFC98200.toInt(), IseePortrait.SEE_INK)
        assertEquals(0xFF0B1220.toInt(), IseePortrait.INK)
        assertEquals(0xFF6A7486.toInt(), IseePortrait.SUB)
        assertEquals(0x2E0B1220, IseePortrait.DIM)
        assertArrayEquals(
            intArrayOf(0xFF0B5FFF.toInt(), 0xFF3FB8FF.toInt(), 0xFFFFC93C.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE8A3.toInt()),
            IseePortrait.IDLE,
        )
        assertArrayEquals(
            intArrayOf(0xFF0A4FD8.toInt(), 0xFF0B5FFF.toInt(), 0xFF4C8DFF.toInt(), 0xFF93BBFF.toInt(), 0xFF3FB8FF.toInt()),
            IseePortrait.PAL_YOU,
        )
        assertArrayEquals(
            intArrayOf(0xFFF59E0B.toInt(), 0xFFFFC93C.toInt(), 0xFFFFB020.toInt(), 0xFFFFE08A.toInt(), 0xFFFF8A3D.toInt()),
            IseePortrait.PAL_SEE,
        )
    }

    @Test
    fun `la geometrie Portrait est figee`() {
        assertEquals(0.40f, IseePortrait.REST_Y, 0f)
        assertEquals(0.335f, IseePortrait.REST_R, 0f)
        assertEquals(0.58f, IseePortrait.PICTURE_H, 0f)
        assertEquals(0.555f, IseePortrait.CAM_Y, 0f)
        assertEquals(0.20f, IseePortrait.CAM_R, 0f)
        assertEquals(0.34f, IseePortrait.FADE, 0f)
    }

    @Test
    fun `l'enveloppe de voix est figee echantillon par echantillon`() {
        assertEquals(0f, voiceEnvelope(0f), 0f)
        assertEquals(0.138603f, voiceEnvelope(1f), 1e-4f)
        assertEquals(0.482936f, voiceEnvelope(2.5f), 1e-4f)
        assertEquals(0.345615f, voiceEnvelope(1f, 3), 1e-4f)
        assertEquals(0.319674f, voiceEnvelope(0.5f, 3), 1e-4f)
    }
}
