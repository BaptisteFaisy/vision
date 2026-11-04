package dev.sam3vision.core.crossing

import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veto panneau : le dos d'un panneau rond et une barre sortaient en feu piéton
 * (0,36 à 0,55) sur les vidéos Samsung du 04/11 et relançaient « Don't cross. »
 * sur un passage sans feu. Boîtes et crops réels, voir `feuxrank/README.md`.
 */
class SignLightVetoTest {

    private val lightBox = listOf(4.9f, 50f, 12.9f, 63.8f)
    private val signBox = listOf(5f, 50f, 13f, 64f)

    private fun light(score: Float, box: List<Float> = lightBox): SegObject =
        SegObject(CrossingPrompts.LIGHT, score, box, "")

    private fun sign(score: Float, box: List<Float> = signBox): SegObject = SegObject("sign", score, box, "")

    @Test
    fun `boite faible dans un panneau mieux note - vetoee`() {
        val weak = light(0.47f)
        assertTrue(SignLightVeto.isVetoed(weak, listOf(weak, sign(0.66f))))
    }

    @Test
    fun `boite franche dans un panneau - gardee`() {
        // Un vrai feu est souvent aussi vu comme panneau : score franc, il reste un feu.
        val strong = light(SignLightVeto.WEAK_SCORE)
        assertFalse(SignLightVeto.isVetoed(strong, listOf(strong, sign(0.9f))))
    }

    @Test
    fun `panneau moins bien note que la boite - gardee`() {
        val weak = light(0.5f)
        assertFalse(SignLightVeto.isVetoed(weak, listOf(weak, sign(0.45f))))
    }

    @Test
    fun `boite qui deborde du panneau - gardee`() {
        val weak = light(0.5f, listOf(0f, 40f, 20f, 70f))
        assertFalse(SignLightVeto.isVetoed(weak, listOf(weak, sign(0.7f))))
    }

    @Test
    fun `sans panneau ou boite illisible - gardee`() {
        val weak = light(0.5f)
        assertFalse(SignLightVeto.isVetoed(weak, listOf(weak)))
        val broken = light(0.5f, listOf(1f, 2f))
        assertFalse(SignLightVeto.isVetoed(broken, listOf(broken, sign(0.7f))))
        assertFalse("autre concept : pas un panneau", SignLightVeto.isVetoed(weak, listOf(weak, SegObject("pole", 0.9f, signBox, ""))))
    }

    @Test
    fun `boites normalisees - memes valeurs que selectLightBoxes`() {
        val objects = listOf(light(0.47f), sign(0.66f))
        val selected = CrossingPrompts.selectLightBoxes(objects, 180, 320)
        assertEquals(selected.toSet(), CrossingPrompts.signLikeBoxes(objects, 180, 320))
        assertTrue(CrossingPrompts.signLikeBoxes(objects, 0, 320).isEmpty())
    }

    @Test
    fun `crops reels - aucune lampe dans le panneau, une lampe dans le feu`() {
        // C'est la lampe qui protège le vrai rouge : sans elle la géométrie écarte
        // la boîte, avec elle le feu passe. Crops de la frame relue par `:app`.
        val loader = javaClass.classLoader
        listOf("samsung-20261104-panneau-rond.ppm", "samsung-20261104-barre.ppm").forEach { name ->
            val (argb, width, height) = pixelsOf(loader, "feuxrank", name)
            assertTrue("$name : pas de lampe", LampLens.flux(argb, width, height) < LampLens.MIN_FLUX)
            assertEquals("$name : jamais vert", CrossingState.Red, PedLightGate.verdict(argb, width, height))
        }
        val (red, width, height) = pixelsOf(loader, "feuxrank", "samsung-20261104-red.ppm")
        assertTrue("vrai feu rouge : lampe lue", LampLens.flux(red, width, height) >= LampLens.MIN_FLUX)
    }
}
