package dev.sam3vision.core.dead

import dev.sam3vision.core.curb.CurbConcepts
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.hazard.FenceOpeningConcepts
import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.live.FreeSpace
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PersonLabels
import dev.sam3vision.core.live.PlacedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Concepts morts (spec v4) : sortis des listes, plus détectés, jamais reconnus. */
class DeadConceptsRemovedTest {

    @Test
    fun `defaut 16 concepts, sans bus velo moto cles ni portail`() {
        assertEquals(16, LivePrompts.default.size)
        assertTrue("car" in LivePrompts.default)
        assertTrue("person" in LivePrompts.default)
        assertTrue("wall" in LivePrompts.default)
        assertFalse("bus" in LivePrompts.default)
        assertFalse("bicycle" in LivePrompts.default)
        assertFalse("motorcycle" in LivePrompts.default)
        assertFalse("keys" in LivePrompts.default)
        assertFalse("gate" in LivePrompts.default)
        assertFalse("electric scooter" in LivePrompts.default)
        assertEquals(LivePrompts.default, LivePrompts.forMode(true))
        assertEquals(LivePrompts.indoor, LivePrompts.forMode(false))
    }

    @Test
    fun `danger ignore borne portail potelet et trottinette`() {
        listOf("borne", "bollard", "portail", "gate", "potelet", "plot", "trottinette", "scooter", "electric scooter")
            .forEach { assertFalse(it, HazardConcepts.isHazard(it)) }
        assertTrue(HazardConcepts.isHazard("barrier"))
        assertTrue(HazardConcepts.isHazard("poussette"))
        assertTrue(HazardConcepts.isHazard("chien"))
        assertTrue(HazardConcepts.isHazard("personne"))
    }

    @Test
    fun `stenope ignore bus camion moto velo trottinette borne portail arbre`() {
        listOf(
            "bus", "truck", "camion", "moto", "motorcycle",
            "velo", "bicycle", "bike", "trottinette", "trottinette electrique",
            "scooter", "electric scooter", "bollard", "borne", "plot", "potelet",
            "gate", "portail", "arbre", "tree",
        ).forEach { assertNull(it, KnownSizes.heightMeters(it)) }
        assertNotNull(KnownSizes.heightMeters("car"))
        assertNotNull(KnownSizes.heightMeters("wall"))
        assertNotNull(KnownSizes.heightMeters("fence"))
        assertNotNull(KnownSizes.heightMeters("barrier"))
    }

    @Test
    fun `bordure ignore bateau et curb ramp`() {
        assertFalse(CurbConcepts.isCurb("bateau"))
        assertFalse(CurbConcepts.isCurb("curb ramp"))
        assertFalse(CurbConcepts.isGround("bateau"))
        assertTrue(CurbConcepts.isCurb("bordure"))
        assertTrue(CurbConcepts.isCurb("sidewalk"))
    }

    @Test
    fun `plan continu sous les pieds muet, sans bateau`() {
        val plane = DistanceReading(
            trackId = -1, label = "sidewalk", meters = 0.4f, rawMeters = 0.4f,
            box = listOf(156f, 200f, 356f, 1130f),
        )
        val signal = CurbSignal.of(listOf(plane), 512, 1138)
        assertFalse(signal.detected)
        assertNull(CurbWatcher().onSignal(signal, 1_000L))
        assertNull(CurbWatcher().onSignal(signal, 2_000L))
    }

    @Test
    fun `grillage suit fence et barrier, plus gate`() {
        assertEquals(listOf("fence", "barrier"), FenceOpeningConcepts.PROMPTS)
        assertFalse(FenceOpeningConcepts.isFence("gate"))
        assertFalse(FenceOpeningConcepts.isFence("portail"))
        assertTrue(FenceOpeningConcepts.isFence("fence"))
        assertTrue(FenceOpeningConcepts.isFence("grillage"))
    }

    @Test
    fun `place libre ignore le portail`() {
        val halfWidth = 2f * 2f * LensSpec.halfTangent(LensSpec.DEFAULT_H_FOV_DEG)
        val gate = PlacedLabel("gate", 0.5f + 0.6f / halfWidth, 0.5f, 2f)
        assertEquals(FreeSpace.unknown(), FreeSpace.of(listOf(gate)))
    }

    @Test
    fun `corps qui bloque ignore la trottinette`() {
        assertFalse(PersonLabels.isBody("trottinette"))
        assertFalse(PersonLabels.isBody("scooter"))
        assertFalse(PersonLabels.isBody("electric scooter"))
        assertTrue(PersonLabels.isBody("poussette"))
        assertTrue(PersonLabels.isBody("wheelchair"))
        assertTrue(PersonLabels.isBody("personne"))
    }
}
