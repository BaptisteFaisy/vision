package dev.sam3vision.core.iseedesign

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
}
