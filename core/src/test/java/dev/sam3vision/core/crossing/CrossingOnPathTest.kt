package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Évitement des personnes SUR le passage piéton en mode Traversée : le côté
 * occupé (corps qui touche la boîte du passage, dans l'axe de marche)
 * conseille de passer de l'autre côté — statique compris, contrairement au
 * nudge d'approche ([dev.sam3vision.core.live.LateralNudgeTracker], verrou
 * « statique → rien » intact, autre tracker, autre condition).
 */
class CrossingOnPathTest {

    private val width = 1000
    private val height = 1000
    private val cross = listOf(100f, 600f, 900f, 950f)

    private fun obj(label: String, score: Float = 0.8f, box: List<Float>) =
        SegObject(label = label, score = score, box = box, maskPngB64 = "")

    private fun personOn(feetX: Float, feetY: Float = 0.8f): SegObject {
        val cx = feetX * width
        val foot = feetY * height
        return obj("person", box = listOf(cx - 50f, foot - 400f, cx + 50f, foot))
    }

    private fun reading(box: List<Float>, meters: Float) =
        DistanceReading(trackId = -1, label = "person", meters = meters, rawMeters = meters, box = box)

    private fun tracker() = CrossingOnPathTracker()

    private fun crosswalk() = obj("crosswalk", box = cross)

    @Test
    fun `personne statique a gauche sur passage conseille la droite`() {
        val person = personOn(0.4f)
        val objects = listOf(crosswalk(), person)
        val readings = listOf(reading(person.box, 2.0f))
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height, readings))
        assertEquals(SteeringCap.Right, track.onFrame(200L, objects, width, height, readings))
    }

    @Test
    fun `personne statique a droite sur passage conseille la gauche`() {
        val person = personOn(0.6f)
        val objects = listOf(crosswalk(), person)
        val readings = listOf(reading(person.box, 2.0f))
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height, readings))
        assertEquals(SteeringCap.Left, track.onFrame(200L, objects, width, height, readings))
    }

    @Test
    fun `personne sur passage hors axe reste muette`() {
        val person = personOn(0.15f)
        val objects = listOf(crosswalk(), person)
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertNull(track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `personne au centre du passage reste muette`() {
        val person = personOn(0.5f)
        val objects = listOf(crosswalk(), person)
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertNull(track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `personne sur passage sans distance conseille quand meme`() {
        val person = personOn(0.4f)
        val objects = listOf(crosswalk(), person)
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height, emptyList()))
        assertEquals(SteeringCap.Right, track.onFrame(200L, objects, width, height, emptyList()))
    }

    @Test
    fun `personne trop loin ne conseille pas`() {
        val person = personOn(0.4f)
        val objects = listOf(crosswalk(), person)
        val readings = listOf(reading(person.box, 5.0f))
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height, readings))
        assertNull(track.onFrame(200L, objects, width, height, readings))
    }

    @Test
    fun `des deux cotes sur passage on ne conseille rien`() {
        val objects = listOf(crosswalk(), personOn(0.4f), personOn(0.6f))
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertNull(track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `personne hors passage statique ne conseille rien`() {
        val person = personOn(0.45f, feetY = 0.5f)
        val objects = listOf(crosswalk(), person)
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertNull(track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `sans passage visible on ne conseille rien`() {
        val objects = listOf(personOn(0.4f))
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertNull(track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `entree-sortie fugace ne parle pas`() {
        val on = listOf(crosswalk(), personOn(0.4f))
        val off = listOf(crosswalk())
        val track = tracker()

        assertNull(track.onFrame(0L, on, width, height))
        assertNull(track.onFrame(200L, off, width, height))
        assertNull(track.onFrame(400L, on, width, height))
    }

    @Test
    fun `reset oublie la gene`() {
        val objects = listOf(crosswalk(), personOn(0.4f))
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        track.reset()
        assertNull(track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `piste perimee apres silence ne parle pas tout de suite`() {
        val objects = listOf(crosswalk(), personOn(0.4f))
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertNull(track.onFrame(2000L, objects, width, height))
        assertEquals(SteeringCap.Right, track.onFrame(2200L, objects, width, height))
    }

    @Test
    fun `personne proche pieds coupes parle quand meme`() {
        val person = obj("person", box = listOf(350f, 500f, 450f, 1000f))
        val objects = listOf(crosswalk(), person)
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertEquals(SteeringCap.Right, track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `personne cote cible meme hors passage fait taire`() {
        val occupant = personOn(0.4f)
        val veto = personOn(0.8f, feetY = 0.2f)
        val objects = listOf(crosswalk(), occupant, veto)
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertNull(track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `personne cote occupe hors passage laisse parler`() {
        val occupant = personOn(0.4f)
        val loin = personOn(0.15f, feetY = 0.2f)
        val objects = listOf(crosswalk(), occupant, loin)
        val track = tracker()

        assertNull(track.onFrame(0L, objects, width, height))
        assertEquals(SteeringCap.Right, track.onFrame(200L, objects, width, height))
    }

    @Test
    fun `corps qui touche la boite avec tolerance`() {
        assertTrue(CrossingOnPath.bodyOnCrosswalk(0.25f, 0.4f, 0.35f, 0.8f, 0.1f, 0.6f, 0.9f, 0.95f))
        assertTrue(CrossingOnPath.bodyOnCrosswalk(0.0f, 0.7f, 0.09f, 0.8f, 0.1f, 0.6f, 0.9f, 0.95f))
        assertTrue(CrossingOnPath.bodyOnCrosswalk(0.25f, 0.5f, 0.35f, 1.0f, 0.1f, 0.6f, 0.9f, 0.95f))
        assertFalse(CrossingOnPath.bodyOnCrosswalk(0.0f, 0.7f, 0.05f, 0.8f, 0.1f, 0.6f, 0.9f, 0.95f))
        assertFalse(CrossingOnPath.bodyOnCrosswalk(0.25f, 0.1f, 0.35f, 0.5f, 0.1f, 0.6f, 0.9f, 0.95f))
    }
}
