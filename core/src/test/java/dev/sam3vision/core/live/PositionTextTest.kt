package dev.sam3vision.core.live

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mise en mots des positions : cadran horaire, ordre de lecture, doublons, troncature. */
class PositionTextTest {

    private fun placed(label: String, x: Float, y: Float = 0.5f) = PlacedLabel(label, x, y)

    private fun detection(label: String, box: List<Float>, width: Int = 100, height: Int = 100) =
        Detection(label, 0.9f, box, width, height)

    @Test
    fun `cadran de 9h à gauche à 3h à droite`() {
        assertEquals(9, PositionText.clockHour(0.05f))
        assertEquals(10, PositionText.clockHour(0.2f))
        assertEquals(11, PositionText.clockHour(0.35f))
        assertEquals(12, PositionText.clockHour(0.5f))
        assertEquals(1, PositionText.clockHour(0.65f))
        assertEquals(2, PositionText.clockHour(0.8f))
        assertEquals(3, PositionText.clockHour(0.95f))
        assertEquals(3, PositionText.clockHour(1.0f))
    }

    @Test
    fun `libelle de position au cadran`() {
        assertEquals("à 9 heures", PositionText.clockLabel(0.1f))
        assertEquals("à midi", PositionText.clockLabel(0.5f))
        assertEquals("à 3 heures", PositionText.clockLabel(0.9f))
        assertEquals("à 1 heure", PositionText.clockLabel(0.65f))
        assertEquals("à 11 heures", PositionText.clockLabel(0.35f))
    }

    @Test
    fun `boite en pixels convertie en position normalisee`() {
        val obj = PositionText.fromDetection(detection("chaise", listOf(0f, 0f, 20f, 20f)))

        assertEquals(0.1f, obj!!.centerX, 0.001f)
        assertEquals(0.1f, obj.centerY, 0.001f)
        assertEquals("à 9 heures", obj.position)
    }

    @Test
    fun `detection inutilisable ou label vide est ignoree`() {
        assertNull(PositionText.fromDetection(detection("chaise", listOf(0f, 0f, 20f, 20f), width = 0)))
        assertNull(PositionText.fromDetection(detection(" ", listOf(0f, 0f, 20f, 20f))))
        assertNull(PositionText.fromSeenObject(SeenObject(1, "chien", 0.5f, listOf(0f, 0f))))
        assertNull(PositionText.phrase(emptyList()))
    }

    @Test
    fun `phrase ordonnee de gauche a droite`() {
        val phrase = PositionText.phrase(
            listOf(placed("chien", 0.9f), placed("personne", 0.1f), placed("livre", 0.5f)),
        )

        assertEquals("Personne à 9 heures, livre à midi, chien à 3 heures.", phrase)
    }

    @Test
    fun `meme label meme heure cite une seule fois`() {
        val phrase = PositionText.phrase(listOf(placed("personne", 0.1f), placed("personne", 0.05f)))

        assertEquals("Personne à 9 heures.", phrase)
    }

    @Test
    fun `au dela du plafond le reste est compte`() {
        val labels = listOf(
            placed("personne", 0.05f),
            placed("chien", 0.25f),
            placed("livre", 0.5f),
            placed("table", 0.75f),
            placed("sac", 0.95f),
        )

        val phrase = PositionText.phrase(labels, maxObjects = 3)

        assertTrue(phrase!!.startsWith("Personne à 9 heures, chien à 10 heures, livre à midi"))
        assertTrue(phrase.endsWith(", et 2 autres objets."))
    }

    @Test
    fun `cle de scene insensible a l ordre et aux doublons`() {
        val a = PositionText.key(listOf(placed("chien", 0.9f), placed("personne", 0.1f)))
        val b = PositionText.key(listOf(placed("personne", 0.05f), placed("chien", 0.95f), placed("chien", 0.9f)))

        assertEquals(a, b)
        assertEquals("", PositionText.key(emptyList()))
    }

    @Test
    fun `la distance s'ajoute a l'objet et impose le point-virgule`() {
        val proche = PlacedLabel("personne", 0.1f, 0.5f, meters = 1.44f)
        val loin = PlacedLabel("chien", 0.9f, 0.5f, meters = 3.0f)

        assertEquals(
            "Personne à 9 heures, à 1,4 mètre ; chien à 3 heures, à 3 mètres.",
            PositionText.phrase(listOf(proche, loin)),
        )
        // un seul objet avec distance : même règle, la phrase reste courte
        assertEquals("Personne à 9 heures, à 1,4 mètre.", PositionText.phrase(listOf(proche)))
        // sans distance connue : forme d'origine, virgules
        assertEquals("Personne à 9 heures, chien à 3 heures.", PositionText.phrase(listOf(placed("personne", 0.1f), placed("chien", 0.9f))))
    }

    @Test
    fun `la distance arrondie fait partie de la signature`() {
        val trois = PlacedLabel("personne", 0.5f, 0.5f, meters = 3.2f)
        val deux = PlacedLabel("personne", 0.5f, 0.5f, meters = 2.4f)
        val troisBis = PlacedLabel("personne", 0.5f, 0.5f, meters = 3.4f)

        assertTrue(PositionText.key(listOf(trois)) != PositionText.key(listOf(deux)))
        // même mètre près : pas de nouvelle annonce (le lissage suffit)
        assertEquals(PositionText.key(listOf(trois)), PositionText.key(listOf(troisBis)))
        assertEquals("personne|à midi|2", PositionText.key(listOf(deux)))
        assertEquals("personne|à midi|?", PositionText.key(listOf(PlacedLabel("personne", 0.5f))))
    }
}
