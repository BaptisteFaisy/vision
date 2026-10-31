package dev.sam3vision.core.live

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import dev.sam3vision.core.speech.SpeechLanguage
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
        assertEquals("at 9 o'clock", PositionText.clockLabel(0.1f))
        assertEquals("at 12 o'clock", PositionText.clockLabel(0.5f))
        assertEquals("at 3 o'clock", PositionText.clockLabel(0.9f))
        assertEquals("at 1 o'clock", PositionText.clockLabel(0.65f))
        assertEquals("at 11 o'clock", PositionText.clockLabel(0.35f))
    }

    @Test
    fun `boite en pixels convertie en position normalisee`() {
        val obj = PositionText.fromDetection(detection("chaise", listOf(0f, 0f, 20f, 20f)))

        assertEquals(0.1f, obj!!.centerX, 0.001f)
        assertEquals(0.1f, obj.centerY, 0.001f)
        assertEquals("at 9 o'clock", obj.position)
    }

    @Test
    fun `detection inutilisable ou label vide est ignoree`() {
        assertNull(PositionText.fromDetection(detection("chaise", listOf(0f, 0f, 20f, 20f), width = 0)))
        assertNull(PositionText.fromDetection(detection(" ", listOf(0f, 0f, 20f, 20f))))
        assertNull(PositionText.fromSeenObject(SeenObject(1, "chien", 0.5f, listOf(0f, 0f))))
        assertNull(PositionText.phrase(emptyList()))
    }

    @Test
    fun `sans distance, le plus en face se dit d'abord`() {
        val phrase = PositionText.phrase(
            listOf(placed("chien", 0.9f), placed("personne", 0.1f), placed("livre", 0.5f)),
        )

        assertEquals("Livre at 12 o'clock, chien at 3 o'clock, personne at 9 o'clock.", phrase)
    }

    @Test
    fun `le proche et centre passe devant le lointain lateral`() {
        val loin = PlacedLabel("table", 0.05f, 0.5f, meters = 5f)
        val milieu = PlacedLabel("chien", 0.9f, 0.5f, meters = 2f)
        val proche = PlacedLabel("personne", 0.5f, 0.5f, meters = 1f)

        val phrase = PositionText.phrase(listOf(loin, milieu, proche), maxObjects = 2)

        assertEquals("Personne 1 meter away, chien 2 meters away, and 1 other object.", phrase)
    }

    @Test
    fun `a distance egale, le centre passe devant les cotes`() {
        val gauche = PlacedLabel("table", 0.1f, 0.5f, meters = 2f)
        val centre = PlacedLabel("personne", 0.5f, 0.5f, meters = 2f)

        val phrase = PositionText.phrase(listOf(gauche, centre))

        assertEquals("Personne 2 meters away, table 2 meters away.", phrase)
    }

    @Test
    fun `meme label meme heure cite une seule fois`() {
        val phrase = PositionText.phrase(listOf(placed("personne", 0.1f), placed("personne", 0.05f)))

        assertEquals("Personne at 9 o'clock.", phrase)
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

        assertTrue(phrase!!.startsWith("Livre at 12 o'clock, chien at 10 o'clock, table at 2 o'clock"))
        assertTrue(phrase.endsWith(", and 2 other objects."))
    }

    @Test
    fun `cle de scene insensible a l ordre et aux doublons`() {
        val a = PositionText.key(listOf(placed("chien", 0.9f), placed("personne", 0.1f)))
        val b = PositionText.key(listOf(placed("personne", 0.05f), placed("chien", 0.95f), placed("chien", 0.9f)))

        assertEquals(a, b)
        assertEquals("", PositionText.key(emptyList()))
    }

    @Test
    fun `la distance remplace le cadran, au metre entier`() {
        val proche = PlacedLabel("personne", 0.1f, 0.5f, meters = 1.44f)
        val loin = PlacedLabel("chien", 0.9f, 0.5f, meters = 3.0f)

        assertEquals(
            "Personne 1 meter away, chien 3 meters away.",
            PositionText.phrase(listOf(proche, loin)),
        )
        // un seul objet avec distance : même règle, la phrase reste courte
        assertEquals("Personne 1 meter away.", PositionText.phrase(listOf(proche)))
        // sans distance connue : le cadran reste, l'ordre dit qui est en face
        assertEquals("Chien at 3 o'clock, personne at 9 o'clock.", PositionText.phrase(listOf(placed("personne", 0.1f), placed("chien", 0.9f))))
    }

    @Test
    fun `la distance arrondie fait partie de la signature`() {
        val trois = PlacedLabel("personne", 0.5f, 0.5f, meters = 3.2f)
        val deux = PlacedLabel("personne", 0.5f, 0.5f, meters = 2.4f)
        val troisBis = PlacedLabel("personne", 0.5f, 0.5f, meters = 3.4f)

        assertTrue(PositionText.key(listOf(trois)) != PositionText.key(listOf(deux)))
        // même mètre près : pas de nouvelle annonce (le lissage suffit)
        assertEquals(PositionText.key(listOf(trois)), PositionText.key(listOf(troisBis)))
        assertEquals("personne|at 12 o'clock|2", PositionText.key(listOf(deux)))
        assertEquals("personne|at 12 o'clock|?", PositionText.key(listOf(PlacedLabel("personne", 0.5f))))
    }

    @Test
    fun `withHour situe au cadran`() {
        assertEquals(
            "Attention à la barrière, à 2 heures.",
            PositionText.withHour("Attention à la barrière", 0.8f, SpeechLanguage.Fr),
        )
        assertEquals(
            "Attention à la marche, à midi.",
            PositionText.withHour("Attention à la marche", 0.5f, SpeechLanguage.Fr),
        )
        assertEquals(
            "Escalier montant, à 11 heures.",
            PositionText.withHour("Escalier montant", 0.3f, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `withHour anglais et sans heure`() {
        assertEquals(
            "Attention, barrier, at 2 o'clock.",
            PositionText.withHour("Attention, barrier", 0.8f, SpeechLanguage.En),
        )
        assertEquals(
            "Attention à la barrière.",
            PositionText.withHour("Attention à la barrière", null, SpeechLanguage.Fr),
        )
    }
}
