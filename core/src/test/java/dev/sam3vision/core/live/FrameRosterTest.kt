package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce qu'une frame donne à lire (étiquettes) et à dire (objets placés, avec leur distance).
 *
 * Ces deux chemins tournent à chaque frame publiée ; ils allouaient une table de hachage
 * et une copie d'objet par détection. Ils sont désormais purs, réutilisables et testés
 * sans téléphone.
 */
class FrameRosterTest {

    private val labels = FrameLabels()

    private fun obj(label: String, box: List<Float> = BOX) =
        SegObject(label = label, score = 0.9f, box = box, maskPngB64 = "")

    private fun frame(objects: List<SegObject>) =
        LiveFrame(width = 200, height = 400, elapsedMs = 90L, objects = objects)

    private fun reading(box: List<Float>, meters: Float) = DistanceReading(
        trackId = 1,
        label = "objet",
        meters = meters,
        rawMeters = meters,
        box = box,
    )

    @Test
    fun `les etiquettes sont dedoublonnees dans l ordre de premiere apparition`() {
        val result = labels.of(listOf(obj("chien"), obj("personne"), obj("chien"), obj("chaise")))
        assertEquals(listOf("chien", "personne", "chaise"), result)
    }

    @Test
    fun `l ensemble reutilise ne garde rien de la frame precedente`() {
        labels.of(listOf(obj("chien"), obj("personne")))
        val next = labels.of(listOf(obj("chaise")))
        assertEquals("aucune étiquette fantôme", listOf("chaise"), next)
        assertTrue("frame sans objet", labels.of(emptyList()).isEmpty())
    }

    @Test
    fun `une boite structurellement egale retrouve sa distance`() {
        // Les lectures rendues par `DistanceMeter` partagent la boîte de l'objet, mais la
        // table doit aussi reconnaître une boîte *égale* sans être la même instance : le
        // contrat d'avant (`associate`) comparait déjà les valeurs, pas les références.
        val readings = listOf(
            reading(listOf(0f, 0f, 5f, 5f), 9f),
            reading(BOX.toList(), 1.4f),
        )
        val placed = placedLabels(frame(listOf(obj("chien"))), readings)
        assertEquals(1, placed.size)
        assertEquals("boîte égale, pas la même instance", 1.4f, placed[0].meters!!, 0.0001f)
    }

    @Test
    fun `une boite sans lecture ne porte pas de distance`() {
        val readings = listOf(reading(listOf(0f, 0f, 5f, 5f), 9f))
        val placed = placedLabels(frame(listOf(obj("chien"))), readings)
        assertEquals(1, placed.size)
        assertNull("aucune distance inventée", placed[0].meters)
    }

    @Test
    fun `les objets places portent leur distance quand elle est mesuree`() {
        val placed = placedLabels(frame(listOf(obj("chien"))), listOf(reading(BOX, 2.5f)))
        assertEquals(1, placed.size)
        assertEquals("chien", placed[0].label)
        assertEquals(2.5f, placed[0].meters!!, 0.0001f)
        // Boîte (10,20)-(110,220) sur 200×400 : centre (0,30 ; 0,30).
        assertEquals(0.30f, placed[0].centerX, 0.01f)
        assertEquals(0.30f, placed[0].centerY, 0.01f)
    }

    @Test
    fun `un objet sans distance est place quand meme, avec meters null`() {
        val placed = placedLabels(frame(listOf(obj("chaise"))), emptyList())
        assertEquals(1, placed.size)
        assertNull("pas de distance inventée", placed[0].meters)
    }

    @Test
    fun `une geometrie inutilisable est omise`() {
        val truncated = listOf(1f, 2f)
        val placed = placedLabels(frame(listOf(obj("chien", truncated), obj("personne"))), emptyList())
        assertEquals(listOf("personne"), placed.map { it.label })
    }

    private companion object {
        /** Boîte de référence, en pixels, dans le repère 200×400 des frames de test. */
        val BOX = listOf(10f, 20f, 110f, 220f)
    }
}