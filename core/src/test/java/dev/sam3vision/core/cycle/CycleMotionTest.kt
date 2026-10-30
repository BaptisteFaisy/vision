package dev.sam3vision.core.cycle

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mouvement des cycles sur boîtes SAM : association IoU entre frames,
 * vitesse lissée ([MotionModel] réutilisé), critères latéral et d'approche,
 * heuristique cavalier. Séquences synthétiques, aucune donnée réelle.
 */
class CycleMotionTest {

    @Test
    fun `velo lateral - en mouvement des la vitesse etablie`() {
        val motion = CycleMotion()
        assertTrue(motion.update(listOf(bikeAt(270f)), atMs = 0L).movingKeys.isEmpty())
        assertTrue(motion.update(listOf(bikeAt(290f)), atMs = 100L).movingKeys.isEmpty())
        val third = motion.update(listOf(bikeAt(310f)), atMs = 200L)

        assertEquals(setOf("bicycle"), third.movingKeys)
        assertTrue("pas d'approche : largeur stable", third.approachKeys.isEmpty())
        assertTrue(third.stillKeys.isEmpty())
        assertEquals(1, motion.trackCount)
    }

    @Test
    fun `trottinette qui approche - en mouvement par croissance`() {
        val motion = CycleMotion()
        motion.update(listOf(scooter(100f)), atMs = 0L)
        motion.update(listOf(scooter(110f)), atMs = 100L)
        val third = motion.update(listOf(scooter(120f)), atMs = 200L)

        assertEquals(setOf("electric scooter"), third.movingKeys)
        assertEquals(setOf("electric scooter"), third.approachKeys)
        assertTrue(third.stillKeys.isEmpty())
    }

    @Test
    fun `velo immobile - a l'arret`() {
        val motion = CycleMotion()
        motion.update(listOf(bikeAt(270f)), atMs = 0L)
        motion.update(listOf(bikeAt(270f)), atMs = 100L)
        val third = motion.update(listOf(bikeAt(270f)), atMs = 200L)

        assertTrue(third.movingKeys.isEmpty())
        assertEquals(setOf("bicycle"), third.stillKeys)
    }

    @Test
    fun `cavalier - roule probable meme immobile`() {
        val motion = CycleMotion()
        val frames = listOf(0L, 100L, 200L).map { atMs ->
            motion.update(listOf(bikeAt(270f), rider()), atMs = atMs)
        }

        assertTrue(frames[0].movingKeys.isEmpty())
        assertTrue(frames[1].movingKeys.isEmpty())
        assertEquals(setOf("bicycle"), frames[2].riderKeys)
        assertEquals("personne dessus = roule probable", setOf("bicycle"), frames[2].movingKeys)
    }

    @Test
    fun `pieton loin - velo a l'arret`() {
        val motion = CycleMotion()
        motion.update(listOf(bikeAt(270f), stranger()), atMs = 0L)
        motion.update(listOf(bikeAt(270f), stranger()), atMs = 100L)
        val third = motion.update(listOf(bikeAt(270f), stranger()), atMs = 200L)

        assertTrue(third.riderKeys.isEmpty())
        assertTrue(third.movingKeys.isEmpty())
        assertEquals(setOf("bicycle"), third.stillKeys)
    }

    @Test
    fun `voiture ignoree - ni mouvement ni arret`() {
        val motion = CycleMotion()
        motion.update(listOf(car(100f)), atMs = 0L)
        motion.update(listOf(car(110f)), atMs = 100L)
        val third = motion.update(listOf(car(120f)), atMs = 200L)

        assertTrue(third.movingKeys.isEmpty())
        assertTrue(third.stillKeys.isEmpty())
        assertEquals(0, motion.trackCount)
    }

    @Test
    fun `jeune piste - doute, ni mouvement ni arret`() {
        val motion = CycleMotion()
        val first = motion.update(listOf(bikeAt(270f)), atMs = 0L)

        assertEquals(CycleVerdict.none(), first)
    }

    @Test
    fun `piste perimee - oubliee, pas de verdict fantome`() {
        val motion = CycleMotion()
        motion.update(listOf(bikeAt(270f)), atMs = 0L)
        motion.update(listOf(bikeAt(290f)), atMs = 100L)
        motion.update(listOf(bikeAt(310f)), atMs = 200L)
        val late = motion.update(listOf(bikeFar()), atMs = 2000L)

        assertTrue("piste initiale périmée, nouvelle piste sans vitesse", late.movingKeys.isEmpty())
        assertTrue(late.stillKeys.isEmpty())
        assertEquals(1, motion.trackCount)
    }

    @Test
    fun `reset - oublie tout`() {
        val motion = CycleMotion()
        motion.update(listOf(bikeAt(270f)), atMs = 0L)
        motion.reset()

        assertEquals(0, motion.trackCount)
        assertTrue(motion.update(listOf(bikeAt(290f)), atMs = 100L).movingKeys.isEmpty())
    }

    /** Vélo 100×60 px dont le bord gauche est à [x1] (bande médiane). */
    private fun bikeAt(x1: Float): Detection =
        Detection("bicycle", 0.9f, listOf(x1, 210f, x1 + 100f, 270f), width = 640, height = 480)

    /** Trottinette centrée de [widthPx] de large (ratio 5:3). */
    private fun scooter(widthPx: Float): Detection =
        Detection("electric scooter", 0.9f, centered(widthPx), width = 640, height = 480)

    /** Voiture centrée (non-cycle : ignorée). */
    private fun car(widthPx: Float): Detection =
        Detection("car", 0.9f, centered(widthPx), width = 640, height = 480)

    /** Cavalier : chevauche la boîte du [bikeAt] (IoU ≈ 0,31). */
    private fun rider(): Detection =
        Detection("person", 0.9f, listOf(280f, 150f, 340f, 260f), width = 640, height = 480)

    /** Passant loin du vélo (IoU nulle). */
    private fun stranger(): Detection =
        Detection("person", 0.9f, listOf(10f, 10f, 60f, 120f), width = 640, height = 480)

    /** Vélo ailleurs dans l'image (autre piste que [bikeAt]). */
    private fun bikeFar(): Detection =
        Detection("bicycle", 0.9f, listOf(10f, 300f, 110f, 360f), width = 640, height = 480)

    private fun centered(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }
}
