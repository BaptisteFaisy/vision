package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approche véhicules sur boîtes SAM : association IoU entre frames, vitesse
 * de largeur lissée ([MotionModel] réutilisé), temps-avant-collision
 * tau = largeur / vitesse. Séquences synthétiques, aucune donnée réelle.
 */
class VehicleApproachTest {

    @Test
    fun `voiture qui approche - veto des la 3e frame avec TTC`() {
        val approach = VehicleApproach()
        assertFalse(approach.update(listOf(car(100f)), atMs = 0L).approaching)
        assertFalse(approach.update(listOf(car(110f)), atMs = 100L).approaching)
        val third = approach.update(listOf(car(120f)), atMs = 200L)

        assertTrue("croissance lisse + vitesse établie", third.approaching)
        assertNotNull(third.ttcSeconds)
        assertTrue("tau ~2,4 s", third.ttcSeconds!! in 1.0f..3.0f)
        assertEquals("car", third.label)
    }

    @Test
    fun `voiture qui s eloigne - jamais de veto`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(120f)), atMs = 0L)
        approach.update(listOf(car(110f)), atMs = 100L)
        val third = approach.update(listOf(car(100f)), atMs = 200L)

        assertFalse(third.approaching)
        assertNull(third.ttcSeconds)
    }

    @Test
    fun `voiture arretee - jamais de veto`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.update(listOf(car(100f)), atMs = 100L)
        val third = approach.update(listOf(car(100f)), atMs = 200L)

        assertFalse(third.approaching)
    }

    @Test
    fun `deux vehicules - pistes independantes, le plus dangereux gagne`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f), busStatic()), atMs = 0L)
        approach.update(listOf(car(110f), busStatic()), atMs = 100L)
        val third = approach.update(listOf(car(120f), busStatic()), atMs = 200L)

        assertTrue(third.approaching)
        assertEquals("car", third.label)
    }

    @Test
    fun `piste perimee - oubliee, pas de veto fantome`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.update(listOf(car(110f)), atMs = 100L)
        approach.update(listOf(car(120f)), atMs = 200L)
        val late = approach.update(listOf(carFar()), atMs = 2000L)

        assertFalse("piste initiale périmée, nouvelle piste sans vitesse", late.approaching)
        assertEquals(1, approach.trackCount)
    }

    @Test
    fun `toute petite boite - ignoree malgre la croissance`() {
        val approach = VehicleApproach()
        approach.update(listOf(tiny(4f)), atMs = 0L)
        approach.update(listOf(tiny(6f)), atMs = 100L)
        val third = approach.update(listOf(tiny(8f)), atMs = 200L)

        assertFalse("moins de 2 % de la largeur : bruit lointain", third.approaching)
    }

    @Test
    fun `score faible ou non vehicule - ignores`() {
        val approach = VehicleApproach()
        val weak = Detection("car", 0.5f, box(100f), width = 640, height = 480)
        val person = Detection("person", 0.9f, box(100f), width = 640, height = 480)
        approach.update(listOf(weak, person), atMs = 0L)
        approach.update(listOf(weak, person), atMs = 100L)
        val third = approach.update(listOf(weak, person), atMs = 200L)

        assertFalse(third.approaching)
        assertEquals(0, approach.trackCount)
    }

    @Test
    fun `reset - oublie tout`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.reset()

        assertEquals(0, approach.trackCount)
        assertFalse(approach.update(listOf(car(200f)), atMs = 100L).approaching)
    }

    @Test
    fun `croissance lente sous le seuil - pas de veto`() {
        // V1 (signal fond absent) : sans fond, seule une croissance franche parle.
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.update(listOf(car(100.25f)), atMs = 100L)
        val third = approach.update(listOf(car(100.5f)), atMs = 200L)

        assertFalse("2,5 px/s lissés sous 3,2 px/s : bruit", third.approaching)
        assertNull(third.ttcSeconds)
        assertEquals("piste suivie mais muette", 1, approach.trackCount)
    }

    @Test
    fun `glissade laterale sans croissance - pas de veto approche`() {
        // V1 (signal fond absent) : la boîte qui glisse à largeur constante
        // n'est pas une approche — sur fond immobile, ce sera une caméra.
        val approach = VehicleApproach()
        approach.update(listOf(carAt(100f, 300f)), atMs = 0L)
        approach.update(listOf(carAt(100f, 320f)), atMs = 100L)
        val third = approach.update(listOf(carAt(100f, 340f)), atMs = 200L)

        assertFalse("latéral franc mais largeur stable", third.approaching)
        assertNull(third.ttcSeconds)
        assertNull(third.label)
        assertEquals(1, approach.trackCount)
    }

    /** Voiture centrée de [widthPx] de large (ratio voiture 5:3). */
    private fun car(widthPx: Float): Detection =
        Detection("car", 0.9f, box(widthPx), width = 640, height = 480)

    /** Voiture de [widthPx] de large centrée en [cx] (glissade à largeur constante). */
    private fun carAt(widthPx: Float, cx: Float): Detection {
        val heightPx = widthPx * 0.6f
        return Detection(
            "car",
            0.9f,
            listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f),
            width = 640,
            height = 480,
        )
    }

    /** Bus statique à droite, loin de la voiture (IoU nulle entre les deux). */
    private fun busStatic(): Detection =
        Detection("bus", 0.9f, listOf(500f, 300f, 600f, 360f), width = 640, height = 480)

    /** Voiture ailleurs dans l'image (autre piste que [car]). */
    private fun carFar(): Detection =
        Detection("car", 0.9f, listOf(10f, 10f, 110f, 70f), width = 640, height = 480)

    /** Toute petite boîte centrée (bruit lointain). */
    private fun tiny(widthPx: Float): Detection =
        Detection("car", 0.9f, box(widthPx), width = 640, height = 480)

    private fun box(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }
}
