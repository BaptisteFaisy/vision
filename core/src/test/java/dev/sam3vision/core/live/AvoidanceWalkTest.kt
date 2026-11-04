package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Marche simulée en boucle fermée : les seuils d'évitement, et le modèle (caméra,
 * passage, marcheur, déterminisme). Le tableau sort dans le `<system-out>` du rapport
 * JUnit et dans `build/avoidance-walk/walk-baseline.txt`, à côté de la trace du premier
 * contact ou de la première dérive de chaque scène (`contacts-5hz.txt`).
 */
class AvoidanceWalkTest {

    private val camera = SimCamera()
    private val straight = WalkPose(0.0, 0.0, 0.0)

    /** Mètres rendus par le vrai `DistanceMeter` pour [obstacle] vu sans bruit depuis l'origine. */
    private fun metersOf(obstacle: Obstacle): Float? {
        val box = camera.boxOf(obstacle.facadeFrom(0.0, 0.0, 0L), straight) ?: return null
        val meter = DistanceMeter().apply { pose = camera.pose }
        val detection = Detection(obstacle.label, 1f, box.toList(), camera.width, camera.height)
        return meter.measure(camera.width, camera.height, listOf(detection)).firstOrNull()?.meters
    }

    @Test
    fun `le tableau baseline sort pour les deux cadences`() {
        val report = WalkReport.render()
        println(report)
        val folder = File("build/avoidance-walk").apply { mkdirs() }
        File(folder, "walk-baseline.txt").writeText(report)
        File(folder, "contacts-5hz.txt").writeText(WalkReport.contactTraces(WalkReport.RATES_HZ.first()))
        assertTrue(report.contains("S14"))
    }

    @Test
    fun `la marche garde ses ecarts aux deux cadences`() {
        for (rate in WalkReport.RATES_HZ) {
            val stats = WalkReport.playAll(rate)
            val contacts = stats.sumOf { it.contacts }
            assertTrue("$rate Hz : $contacts contacts", contacts <= MAX_CONTACTS)
            for (scene in stats.filter { it.scene.id in FRONTAL_SCENES }) {
                assertEquals("$rate Hz, ${scene.scene.id} : on sort par le bout", 0, scene.contacts)
            }
            val alongWall = stats.first { it.scene.id == "S10" }
            assertTrue("$rate Hz : ${alongWall.driftM} m le long du mur", alongWall.driftM <= MAX_WALL_DRIFT_M)
        }
    }

    @Test
    fun `la vraie mesure retrouve la distance projetee`() {
        assertEquals(52.8f, camera.hfovDeg, 0.1f)
        assertEquals(5f, metersOf(person(0.0, 5.0))!!, 0.15f)
        assertEquals(6f, metersOf(pole(0.0, 6.0))!!, 0.15f)
        assertEquals(5f, metersOf(barrier(-0.4, 0.4, 5.0))!!, 0.15f)
        assertEquals(6f, metersOf(wall(-1.0, 6.0, 1.0, 6.0))!!, 0.15f)
    }

    @Test
    fun `sommet coupe mesure au sol, pieds coupes sans distance`() {
        assertEquals("3 m de haut à 2,5 m : le pied reste au sol", 2.5f, metersOf(pole(0.0, 2.5))!!, 0.15f)
        assertNull("pieds sous le cadre à 1,2 m", metersOf(person(0.0, 1.2)))
    }

    @Test
    fun `derriere ou hors champ il n existe pas`() {
        assertNull(camera.boxOf(pole(0.0, -2.0).facadeFrom(0.0, 0.0, 0L), straight))
        assertNull(camera.boxOf(pole(6.0, 4.0).facadeFrom(0.0, 0.0, 0L), straight))
    }

    @Test
    fun `mur parallele du bord d image a son point le plus lointain`() {
        val box = camera.boxOf(wall(-1.2, -2.0, -1.2, 30.0).facadeFrom(0.0, 0.0, 0L), straight)!!
        assertEquals(0.0, box.x0, 1e-6)
        assertTrue("fin du mur près du point de fuite : ${box.x1}", box.x1 in 170.0..191.5)
    }

    @Test
    fun `le passage serveur juge les personnes seules`() {
        val w = camera.width
        val h = camera.height
        val inFront = SegObject("person", 1f, listOf(170f, 100f, 200f, 300f), "")
        val onLeft = SegObject("person", 1f, listOf(10f, 100f, 40f, 300f), "")
        val pole = SegObject("pole", 1f, listOf(180f, 100f, 200f, 300f), "")
        assertFalse(ServerPassage.suggest(listOf(pole), w, h).blocked)
        assertEquals("right", ServerPassage.suggest(listOf(inFront, onLeft), w, h).side)
    }

    @Test
    fun `le marcheur vire tient puis revient`() {
        val walker = Walker(Random(1L))
        walker.hear("left.", 0L)
        repeat(8) { walker.step(WalkModel.STEP_MS, it * WalkModel.STEP_MS) }
        assertEquals(-Walker.TURN_DEG, walker.commandDeg, 1e-6)
        repeat(20) { walker.step(WalkModel.STEP_MS, 400L + it * WalkModel.STEP_MS) }
        assertTrue("revenu après le maintien : ${walker.commandDeg}", walker.commandDeg > -Walker.TURN_DEG + 1.0)
        walker.hear("Straight.", 1_500L)
        repeat(40) { walker.step(WalkModel.STEP_MS, 1_500L + it * WalkModel.STEP_MS) }
        assertEquals(0.0, walker.commandDeg, 1e-6)
    }

    @Test
    fun `meme seed meme marche`() {
        val scene = WALK_SCENES.first()
        assertEquals(WalkRun(scene, 7L, 5.0).play(), WalkRun(scene, 7L, 5.0).play())
    }

    private companion object {
        /** Contacts tolérés sur 280 marches : 50 et 33 avant `ClearPath`, 10 et 7 après. */
        const val MAX_CONTACTS = 12

        /** Barrières et mur de face : la sortie est évidente, aucun contact. */
        val FRONTAL_SCENES = setOf("S7", "S8", "S9")

        /** Dérive tolérée le long d'un mur à 0,55 m d'épaule (m) : rien n'y écarte le marcheur. */
        const val MAX_WALL_DRIFT_M = 0.3
    }
}
