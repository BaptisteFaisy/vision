package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtre distances iSee : voitures et bordures seules dehors, rien dedans. */
class IseeDistanceFilterTest {

    @Test
    fun `voitures affichees, autres vehicules masques`() {
        listOf("car", "Car", "CARS", "voiture", "Voitures").forEach {
            assertTrue(it, IseeDistanceFilter.isShown(it))
        }
        listOf("bus", "van", "camionnette", "truck", "motorcycle", "bicycle").forEach {
            assertFalse(it, IseeDistanceFilter.isShown(it))
        }
    }

    @Test
    fun `bordures affichees, trottoir masque`() {
        listOf("curb", "Curb", "curbs", "kerb", "bordure", "Bordures", "bord", "curbstone").forEach {
            assertTrue(it, IseeDistanceFilter.isShown(it))
        }
        listOf("sidewalk", "trottoir", "person", "pole", "crosswalk", "step", "  ").forEach {
            assertFalse(it, IseeDistanceFilter.isShown(it))
        }
    }

    @Test
    fun `dehors, explicites plus voitures bordures`() {
        assertEquals(IseeDistanceFilter.LABELS, IseeDistanceFilter.followedFor(LivePrompts.default, true))
        val withWallet = LivePrompts.withConcept(LivePrompts.default, "wallet")
        assertEquals(listOf("wallet") + IseeDistanceFilter.LABELS, IseeDistanceFilter.followedFor(withWallet, true))
    }

    @Test
    fun `dedans, explicites seuls`() {
        assertEquals(emptyList<String>(), IseeDistanceFilter.followedFor(LivePrompts.indoor, false))
        val withWallet = LivePrompts.withConcept(LivePrompts.indoor, "wallet")
        assertEquals(listOf("wallet"), IseeDistanceFilter.followedFor(withWallet, false))
    }
}
