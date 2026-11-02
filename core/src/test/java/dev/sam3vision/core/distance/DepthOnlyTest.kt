package dev.sam3vision.core.distance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KnownSizes.isDepthOnly] : le défaut profondeur se reconnaît sous toutes ses formes. */
class DepthOnlyTest {

    @Test
    fun `profondeur reconnue - casse et accents indifferents`() {
        listOf("stairs", "Stairs", "STAIRS", "sidewalk", "crosswalk", "pole", "curb", "bordure", "Bordure").forEach {
            assertTrue(it, KnownSizes.isDepthOnly(it))
        }
    }

    @Test
    fun `pluriel simple reconnu`() {
        assertTrue(KnownSizes.isDepthOnly("curbs"))
        assertTrue(KnownSizes.isDepthOnly("poles"))
    }

    @Test
    fun `calibre ou inconnu - pas profondeur`() {
        listOf("personne", "person", "voiture", "porte", "reflet", "", "   ").forEach {
            assertFalse(it, KnownSizes.isDepthOnly(it))
        }
    }
}
