package dev.sam3vision.app.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branchement de la prédiction locale des boîtes (T8) : flag éteint, le direct reste
 * celui d'avant ; flag allumé, la boîte continue d'avancer entre deux réponses, prend
 * le relais du masque vieilli, puis s'efface au lieu de mentir.
 */
class LivePredictionTest {

    private val width = 512
    private val height = 288

    /** Une réponse `/live` : des objets **sans id**, un carré de 80 px par objet. */
    private fun frame(vararg centersX: Float): LiveFrame = LiveFrame(
        width = width,
        height = height,
        elapsedMs = 40L,
        objects = centersX.map { center ->
            SegObject(
                label = "personne",
                score = 0.9f,
                box = listOf(center - HALF, 100f, center + HALF, 180f),
                maskPngB64 = "",
            )
        },
    )

    @Test
    fun `flag eteint rien n'est suivi ni dessine`() {
        val prediction = LivePrediction(enabled = false)

        assertNull(prediction.absorb(frame(100f), 0L, width, height))
        assertEquals(0, prediction.trackedCount)
        assertTrue(prediction.boxesAt(0L, width, height).empty)
    }

    @Test
    fun `le meme objet garde son id et sa couleur`() {
        val prediction = LivePrediction(enabled = true)

        prediction.absorb(frame(100f, 300f), 0L, width, height)
        prediction.absorb(frame(120f, 320f), 200L, width, height)

        assertEquals(2, prediction.trackedCount)
        val layer = prediction.boxesAt(400L, width, height)
        assertEquals(listOf(0, 1), layer.boxes.map { it.colorIndex })
        assertEquals(width, layer.width)
        assertEquals(height, layer.height)
        assertEquals("personne", layer.boxes.first().label)
        assertFalse(layer.empty)
    }

    @Test
    fun `entre deux reponses la boite avance`() {
        val prediction = LivePrediction(enabled = true)

        prediction.absorb(frame(100f), 0L, width, height)
        prediction.absorb(frame(120f), 200L, width, height)
        prediction.absorb(frame(140f), 400L, width, height)

        val box = prediction.boxesAt(600L, width, height).boxes.single().box
        assertEquals(1, prediction.trackedCount)
        assertTrue("la boîte doit dépasser la dernière réponse : ${box.centerX}", box.centerX > 140f)
    }

    @Test
    fun `un masque frais ne se double pas d'une boite`() {
        val prediction = LivePrediction(enabled = true)

        prediction.absorb(frame(100f), 0L, width, height)

        assertTrue(prediction.boxesAt(ANCHOR_WINDOW_MS, width, height).empty)
        assertFalse(prediction.boxesAt(300L, width, height).empty)
    }

    @Test
    fun `une prediction morte s'efface au lieu de mentir`() {
        val prediction = LivePrediction(enabled = true)

        prediction.absorb(frame(100f), 0L, width, height)
        prediction.absorb(frame(120f), 200L, width, height)

        // 200 ms après la dernière réponse : la boîte vit encore.
        assertFalse(prediction.boxesAt(400L, width, height).empty)
        // Au-delà de la confiance nulle (450 ms) : plus rien, même si l'id est encore suivi.
        assertTrue(prediction.boxesAt(700L, width, height).empty)
        assertEquals(1, prediction.trackedCount)
    }

    @Test
    fun `sans reponse l'objet est oublie`() {
        val prediction = LivePrediction(enabled = true)

        prediction.absorb(frame(100f), 0L, width, height)
        prediction.absorb(frame(), 1_500L, width, height)

        assertEquals(0, prediction.trackedCount)
        assertTrue(prediction.boxesAt(1_600L, width, height).empty)
    }

    @Test
    fun `une boite illisible ne cree pas de suivi`() {
        val prediction = LivePrediction(enabled = true)
        val broken = LiveFrame(
            width = width,
            height = height,
            elapsedMs = 40L,
            objects = listOf(SegObject("personne", 0.9f, listOf(1f, 2f), "")),
        )

        prediction.absorb(broken, 0L, width, height)

        assertEquals(0, prediction.trackedCount)
        assertTrue(prediction.boxesAt(300L, width, height).empty)
    }

    @Test
    fun `reset oublie tout`() {
        val prediction = LivePrediction(enabled = true)
        prediction.absorb(frame(100f), 0L, width, height)

        prediction.reset()

        assertEquals(0, prediction.trackedCount)
        assertTrue(prediction.boxesAt(300L, width, height).empty)
    }

    @Test
    fun `publishableLayer ne publie que ce qui a change`() {
        val off = LivePrediction(enabled = false)
        assertNull(off.publishableLayer(300L, width, height, PredictionLayer()))

        val prediction = LivePrediction(enabled = true)
        prediction.absorb(frame(100f), 0L, width, height)
        // Masque encore frais et couche vide : rien à écrire.
        assertNull(prediction.publishableLayer(ANCHOR_WINDOW_MS, width, height, PredictionLayer()))
        // Boîte périmée : la couche est publiée, même vide elle efface l'ancienne.
        val stale = prediction.publishableLayer(300L, width, height, PredictionLayer())
        assertTrue(stale != null && !stale.empty)
        val cleared = prediction.publishableLayer(700L, width, height, stale!!)
        assertTrue(cleared != null && cleared.empty)
    }

    private companion object {
        /** Demi-largeur de la boîte de test : 80 px de côté, assez pour que l'IoU suive. */
        const val HALF = 40f

        /** Fenêtre où le masque serveur est encore considéré comme frais (prédiction muette). */
        const val ANCHOR_WINDOW_MS = 60L
    }
}