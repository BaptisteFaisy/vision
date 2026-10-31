package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtre tête des panneaux : une enseigne sous laquelle on passe ne bloque
 * pas le couloir, un panneau à hauteur d'homme reste un obstacle. Seuils
 * provisoires ([SignConcepts.HEAD_TOP], [SignConcepts.HEAD_RANGE_M]), à
 * calibrer au téléphone (corpus § 14 de `docs/VOIX-DANGER.md`).
 */
class SignConceptsTest {

    @Test
    fun `panneaux reconnus en français et anglais`() {
        listOf(
            "sign", "signboard", "billboard", "panel", "poster", "placard",
            "panneau", "enseigne", "affiche", "pancarte", "écriteau",
            "Sign", "PANNEAU", "Enseignes", "signs",
        ).forEach { label ->
            assertTrue("reconnu $label", SignConcepts.isSign(label))
        }
    }

    @Test
    fun `non-panneaux et vide rejetés`() {
        listOf("personne", "mur", "trottoir", "barrière", "", "   ").forEach { label ->
            assertFalse("rejeté $label", SignConcepts.isSign(label))
        }
    }

    @Test
    fun `composés backend reconnus, feu jamais filtré`() {
        listOf("traffic sign", "stop sign", "no entry sign", "street sign").forEach { label ->
            assertTrue("reconnu $label", SignConcepts.isSign(label))
        }
        assertFalse(SignConcepts.isSign("traffic light"))
        assertFalse(SignConcepts.isSign("pedestrian traffic light"))
        assertTrue(SignConcepts.isHighSign("stop sign", 0.10f, 2f))
        assertFalse(SignConcepts.isHighSign("traffic light", 0.10f, 2f))
        assertFalse(SignConcepts.isHighSign("pedestrian traffic light", 0.10f, 2f))
    }

    @Test
    fun `panneau haut et proche ignoré`() {
        assertTrue(SignConcepts.isHighSign("panneau", 0.10f, 2f))
        assertTrue(SignConcepts.isHighSign("sign", 0.05f, 3.9f))
        assertTrue(SignConcepts.isHighSign("ENSEIGNE", 0.15f, 1f))
    }

    @Test
    fun `panneau à hauteur d'homme gardé`() {
        assertFalse(SignConcepts.isHighSign("panneau", 0.50f, 2f))
        assertFalse(SignConcepts.isHighSign("sign", 0.30f, 1f))
        assertFalse(SignConcepts.isHighSign("affiche", 0.80f, 2f))
    }

    @Test
    fun `frontière HEAD_TOP exclue`() {
        assertEquals(0.22f, SignConcepts.HEAD_TOP, 0.0001f)
        assertFalse(SignConcepts.isHighSign("panneau", SignConcepts.HEAD_TOP, 2f))
        assertTrue(SignConcepts.isHighSign("panneau", SignConcepts.HEAD_TOP - 0.001f, 2f))
    }

    @Test
    fun `panneau haut lointain gardé`() {
        assertEquals(4.0f, SignConcepts.HEAD_RANGE_M, 0.0001f)
        assertFalse(SignConcepts.isHighSign("panneau", 0.10f, 5f))
        assertTrue(SignConcepts.isHighSign("panneau", 0.10f, SignConcepts.HEAD_RANGE_M))
        assertFalse(SignConcepts.isHighSign("panneau", 0.10f, SignConcepts.HEAD_RANGE_M + 0.01f))
    }

    @Test
    fun `distance inconnue gardée par prudence`() {
        assertFalse(SignConcepts.isHighSign("panneau", 0.10f, null))
        assertFalse(SignConcepts.isHighSign("panneau", 0.10f, Float.NaN))
        assertFalse(SignConcepts.isHighSign("panneau", 0.10f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `non-panneau haut et proche jamais filtré`() {
        assertFalse(SignConcepts.isHighSign("personne", 0.10f, 2f))
        assertFalse(SignConcepts.isHighSign("mur", 0.05f, 1f))
        assertFalse(SignConcepts.isHighSign("", 0.10f, 2f))
    }

    @Test
    fun `Guidance ignore le panneau haut mais bloque sur panneau bas`() {
        val high = PlacedLabel("panneau", centerX = 0.5f, centerY = 0.10f, meters = 2f)
        val highDecision = Guidance.decide(listOf(high))

        assertFalse(highDecision.blocked)

        val low = PlacedLabel("panneau", centerX = 0.5f, centerY = 0.50f, meters = 2f)
        val lowDecision = Guidance.decide(listOf(low))

        assertTrue(lowDecision.blocked)
        assertEquals("panneau", lowDecision.obstacle!!.label)
    }
}
