package dev.sam3vision.core.predict

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table d'ids : apparition, vol libre, disparition, rendu masque-vers-boîte. */
class BoxPredictorTest {

    private val width = 512
    private val height = 288

    private fun obj(id: Int, centerX: Float, score: Float = 0.9f) =
        ServerObject(id, "personne", score, Box(centerX - 20f, 100f, centerX + 20f, 180f))

    private fun walking(predictor: BoxPredictor, startMs: Long, steps: Int, stepPx: Float) {
        for (i in 0 until steps) {
            predictor.onServer(listOf(obj(1, 100f + stepPx * i)), startMs + 200L * i, width, height)
        }
    }

    @Test
    fun `une reponse fraiche s'ancre et merite le masque`() {
        val predictor = BoxPredictor()
        predictor.onServer(listOf(obj(1, 100f)), 0L, width, height)
        val shown = predictor.predictAt(10L, width, height).single()
        assertTrue(shown.anchored)
        assertTrue(shown.useMask)
        assertEquals("personne", shown.label)
        assertEquals(1, predictor.size)
    }

    @Test
    fun `entre deux reponses la boite avance et passe au rendu boite`() {
        val predictor = BoxPredictor()
        walking(predictor, 0L, 3, 20f)
        val shown = predictor.predictAt(680L, width, height).single()
        assertFalse(shown.useMask)
        assertTrue(shown.box.centerX > 130f)
        assertTrue(shown.ageMs in 200L..300L)
    }

    @Test
    fun `un id disparait le temps du vol libre puis est oublie`() {
        val predictor = BoxPredictor()
        predictor.onServer(listOf(obj(1, 100f)), 0L, width, height)
        assertEquals(0, predictor.onServer(emptyList(), 400L, width, height).dropped)
        assertEquals(1, predictor.predictAt(400L, width, height).size)
        assertEquals(0, predictor.predictAt(701L, width, height).size)
        assertEquals(1, predictor.onServer(emptyList(), 1300L, width, height).dropped)
        assertEquals(0, predictor.size)
    }

    @Test
    fun `un id duplique dans la meme reponse ne cree qu'un suivi`() {
        val report = BoxPredictor().onServer(
            listOf(obj(1, 100f), obj(1, 300f)),
            0L, width, height,
        )
        assertEquals(1, report.created)
    }

    @Test
    fun `un saut de l'objet est compte sans etre lisse`() {
        val predictor = BoxPredictor()
        walking(predictor, 0L, 3, 20f)
        val report = predictor.onServer(listOf(obj(1, 400f)), 600L, width, height)
        assertEquals(1, report.jumped)
        assertEquals(0, report.anchored)
        assertEquals(400f, predictor.predictAt(600L, width, height).single().box.centerX, 1f)
    }

    @Test
    fun `les boites restent dans l'image`() {
        val predictor = BoxPredictor()
        walking(predictor, 0L, 8, 40f)
        val shown = predictor.predictAt(1650L, width, height).single()
        assertTrue(shown.box.x2 <= width + 0.5f)
        assertTrue(shown.box.x1 >= -0.5f)
    }

    @Test
    fun `le rapport de synchro donne l'IoU moyen predit contre serveur`() {
        val predictor = BoxPredictor()
        walking(predictor, 0L, 4, 20f)
        val report = predictor.onServer(listOf(obj(1, 180f)), 800L, width, height)
        assertTrue(report.meanIou > 0.5f)
        assertEquals(0, report.jumped)
    }

    @Test
    fun `reset vide la table`() {
        val predictor = BoxPredictor()
        predictor.onServer(listOf(obj(1, 100f)), 0L, width, height)
        predictor.reset()
        assertEquals(0, predictor.size)
    }

    @Test
    fun `le flot recale la boite avant la premiere vitesse`() {
        val predictor = BoxPredictor()
        val subject = ServerObject(1, "personne", 0.9f, Box(80f, 60f, 120f, 140f))
        predictor.onServer(listOf(subject), 0L, width, height)
        val still = predictor.predictAt(200L, width, height).single()
        val moved = predictor.predictAt(200L, width, height, texture(240, 240), textureShifted(240, 240, 6))
        assertEquals(100f, still.box.centerX, 0.5f)
        assertTrue(moved.single().box.centerX > 102f)
        assertTrue(moved.single().box.centerX < 108f)
    }

    @Test
    fun `un flot sans information ne deplace rien`() {
        val predictor = BoxPredictor()
        predictor.onServer(listOf(ServerObject(1, "x", 0.9f, Box(80f, 60f, 120f, 140f))), 0L, width, height)
        val flat = flatMask(240, 240)
        val shown = predictor.predictAt(200L, width, height, flat, flat)
        assertEquals(100f, shown.single().box.centerX, 0.5f)
    }

    private fun tex(x: Int, y: Int): Int {
        val v = 60 + 45 * sin(x * 0.63) + 40 * sin(y * 0.87) +
            28 * sin((x + y) * 0.29) + 18 * sin(x * 1.9 + y * 0.7)
        return v.toInt().coerceIn(0, 255)
    }

    private fun texture(w: Int, h: Int) = maskOf(w, h) { x, y -> tex(x, y) }

    private fun textureShifted(w: Int, h: Int, dx: Int) = maskOf(w, h) { x, y -> tex(x - dx, y) }

    private fun flatMask(w: Int, h: Int) = maskOf(w, h) { _, _ -> 128 }

    private fun maskOf(w: Int, h: Int, f: (Int, Int) -> Int): dev.sam3vision.core.mask.GrayMask {
        val pixels = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = f(x, y).toByte()
            }
        }
        return dev.sam3vision.core.mask.GrayMask(w, h, pixels)
    }
}