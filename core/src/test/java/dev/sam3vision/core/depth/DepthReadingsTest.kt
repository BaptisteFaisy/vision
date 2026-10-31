package dev.sam3vision.core.depth

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthReadingsTest {

    /** Carte 100×100 : proximité 10 à gauche, 2 à droite (marche franche à x=50). */
    private fun stepMap(): DepthMap {
        val values = FloatArray(100 * 100) { i -> if (i % 100 < 50) 10f else 2f }
        return DepthMap(100, 100, values)
    }

    @Test
    fun `mediane par boite - cote proche et cote loin`() {
        val map = stepMap()
        val near = DepthReadings.boxCloseness(map, listOf(0f, 0f, 49f, 99f), 100, 100)
        val far = DepthReadings.boxCloseness(map, listOf(50f, 0f, 99f, 99f), 100, 100)
        assertEquals(10f, near!!, 0.001f)
        assertEquals(2f, far!!, 0.001f)
    }

    @Test
    fun `boite hors champ - bornee sans planter, vide - null`() {
        val map = stepMap()
        val clipped = DepthReadings.boxCloseness(map, listOf(-50f, -50f, 10f, 10f), 100, 100)
        assertEquals(10f, clipped!!, 0.001f)
        assertNull(DepthReadings.boxCloseness(map, listOf(0f, 0f, 10f), 100, 100))
        assertNull(DepthReadings.boxCloseness(map, listOf(0f, 0f, 10f, 10f), 0, 100))
    }

    @Test
    fun `mediane sur fenetre centrale, fond des bords ecarte`() {
        // objet 5.0 au centre (cellules 25..74), fond 1.0 autour : la boîte entière
        // est majoritairement du fond, la fenêtre érodée est pure objet
        val values = FloatArray(100 * 100) { i ->
            val x = i % 100
            val y = i / 100
            if (x in 25..74 && y in 25..74) 5f else 1f
        }
        val map = DepthMap(100, 100, values)
        assertEquals(5f, DepthReadings.boxCloseness(map, listOf(0f, 0f, 100f, 100f), 100, 100)!!, 0.001f)
    }

    @Test
    fun `erosion degeneree - repli boite entiere, jamais null abusif`() {
        assertEquals(listOf(25f, 25f, 75f, 75f), DepthReadings.erodedBox(listOf(0f, 0f, 100f, 100f)))
        assertNull(DepthReadings.erodedBox(listOf(0f, 0f, 10f)))
        val values = FloatArray(10 * 10) { 3f }
        val map = DepthMap(10, 10, values)
        // 1 px : fenêtre réduite mais valide
        assertEquals(3f, DepthReadings.boxCloseness(map, listOf(4f, 4f, 5f, 5f), 10, 10)!!, 0.001f)
        // largeur nulle : érosion dégénérée, la boîte entière sert quand même
        assertNull(DepthReadings.erodedBox(listOf(4f, 4f, 4f, 6f)))
        assertEquals(3f, DepthReadings.boxCloseness(map, listOf(4f, 4f, 4f, 6f), 10, 10)!!, 0.001f)
    }

    @Test
    fun `masque - le fond hors masque est exclu de la mediane`() {
        // bande objet 5.0 (x 40..59), fond 1.0 ailleurs : la fenêtre érodée de
        // la boîte pleine image est majoritairement du fond, l'intersection
        // avec le masque est pure objet
        val values = FloatArray(100 * 100) { i ->
            val x = i % 100
            if (x in 40..59) 5f else 1f
        }
        val map = DepthMap(100, 100, values)
        val box = listOf(0f, 0f, 100f, 100f)
        assertEquals(1f, DepthReadings.boxCloseness(map, box, 100, 100)!!, 0.001f)
        assertEquals(
            5f,
            DepthReadings.boxCloseness(map, box, 100, 100, listOf(40f, 0f, 59f, 100f))!!,
            0.001f,
        )
    }

    @Test
    fun `masque - intersection vide ou masque invalide, repli fenetre erodee`() {
        val values = FloatArray(100 * 100) { i ->
            val x = i % 100
            if (x in 40..59) 5f else 1f
        }
        val map = DepthMap(100, 100, values)
        val box = listOf(0f, 0f, 100f, 100f)
        val plain = DepthReadings.boxCloseness(map, box, 100, 100)!!
        // disjoint : intersection vide -> repli
        assertEquals(
            plain,
            DepthReadings.boxCloseness(map, box, 100, 100, listOf(200f, 200f, 300f, 300f))!!,
            0.001f,
        )
        // dégénéré (largeur nulle) -> repli
        assertEquals(
            plain,
            DepthReadings.boxCloseness(map, box, 100, 100, listOf(10f, 10f, 10f, 20f))!!,
            0.001f,
        )
        // non fini -> repli
        assertEquals(
            plain,
            DepthReadings.boxCloseness(map, box, 100, 100, listOf(40f, 0f, Float.NaN, 100f))!!,
            0.001f,
        )
        assertEquals(
            listOf(40f, 25f, 59f, 75f),
            DepthReadings.maskedWindow(listOf(25f, 25f, 75f, 75f), listOf(40f, 0f, 59f, 100f)),
        )
    }

    @Test
    fun `masque - refine propage la confiance du stenepe, pas 1`() {
        val map = stepMap()
        val person = Detection(
            "personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100,
            maskBox = listOf(0f, 0f, 24f, 99f),
        )
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box, 0.6f))
        val out = DepthReadings.refine(100, 100, listOf(person), pinhole, map)
        assertEquals(1, out.size)
        assertEquals(2f, out[0].meters, 0.01f)
        assertEquals(0.6f, out[0].confidence, 0.001f)
    }

    @Test
    fun `echelle - mediane des ancres, vide - null`() {
        val scale = DepthReadings.scaleFromAnchors(
            listOf(DepthAnchor(2f, 10f), DepthAnchor(10f, 2f), DepthAnchor(4f, 5f)),
        )
        assertEquals(20f, scale!!, 0.001f)
        assertNull(DepthReadings.scaleFromAnchors(emptyList()))
        assertNull(DepthReadings.scaleFromAnchors(listOf(DepthAnchor(-1f, 10f))))
    }

    @Test
    fun `affine - remplace ajoute et garde`() {
        val map = stepMap()
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val sidewalk = Detection("trottoir", 0.7f, listOf(50f, 0f, 99f, 99f), 100, 100)
        val ghost = Detection("reflet", 0.4f, listOf(200f, 200f, 300f, 300f), 100, 100)
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box))
        val out = DepthReadings.refine(100, 100, listOf(person, sidewalk, ghost), pinhole, map)
        assertEquals(2, out.size)
        val p = out.first { it.label == "personne" }
        assertEquals(7, p.trackId)
        assertEquals(2f, p.meters, 0.01f)
        val s = out.first { it.label == "trottoir" }
        assertEquals(-1, s.trackId)
        assertEquals(10f, s.meters, 0.01f)
        assertTrue(out.none { it.label == "reflet" })
    }

    @Test
    fun `affine - sans ancre garde le stenepe tel quel`() {
        val map = stepMap()
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box))
        val broken = DepthMap(100, 100, FloatArray(100 * 100) { Float.NaN })
        val out = DepthReadings.refine(100, 100, listOf(person), pinhole, broken)
        assertEquals(pinhole, out)
    }

    @Test
    fun `proximites serveur - finies seulement, par boite`() {
        val person = SegObject("personne", 0.9f, listOf(0f, 0f, 49f, 99f), "", depth = 0.8f)
        val ghost = SegObject("reflet", 0.4f, listOf(1f, 1f, 2f, 2f), "", depth = null)
        val noise = SegObject("bruit", 0.3f, listOf(3f, 3f, 4f, 4f), "", depth = Float.NaN)
        val close = DepthReadings.serverCloseness(listOf(person, ghost, noise))
        assertEquals(mapOf(person.box to 0.8f), close)
    }

    @Test
    fun `affine avec proximites - remplace ajoute et garde`() {
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val sidewalk = Detection("trottoir", 0.7f, listOf(50f, 0f, 99f, 99f), 100, 100)
        val ghost = Detection("reflet", 0.4f, listOf(200f, 200f, 300f, 300f), 100, 100)
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box))
        val close = mapOf(person.box to 0.8f, sidewalk.box to 0.2f)
        val out = DepthReadings.refineWithCloseness(listOf(person, sidewalk, ghost), pinhole, close)
        assertEquals(2, out.size)
        val p = out.first { it.label == "personne" }
        assertEquals(7, p.trackId)
        assertEquals(2f, p.meters, 0.01f)
        val s = out.first { it.label == "trottoir" }
        assertEquals(8f, s.meters, 0.01f)
        assertTrue(out.none { it.label == "reflet" })
    }

    @Test
    fun `colonne centrale - geometrie exacte`() {
        assertEquals(listOf(25f, 0f, 75f, 100f), DepthReadings.centralColumn(listOf(0f, 0f, 100f, 100f)))
        assertEquals(listOf(48.5f, 45f, 51.5f, 55f), DepthReadings.centralColumn(listOf(47f, 45f, 53f, 55f)))
        // largeur nulle : la ligne centrale mesure encore (±1 px)
        assertEquals(listOf(3f, 4f, 5f, 6f), DepthReadings.centralColumn(listOf(4f, 4f, 4f, 6f)))
        assertNull(DepthReadings.centralColumn(listOf(0f, 0f, 10f)))
        assertNull(DepthReadings.centralColumn(listOf(4f, 6f, 6f, 6f)))
        assertNull(DepthReadings.centralColumn(listOf(0f, 0f, Float.NaN, 10f)))
    }

    @Test
    fun `fenetre robuste - erodee quand elle tient, colonne sinon`() {
        assertEquals(listOf(25f, 25f, 75f, 75f), DepthReadings.robustWindow(listOf(0f, 0f, 100f, 100f)))
        // érodée 3x5 px : trop petite, colonne centrale pleine hauteur
        assertEquals(listOf(48.5f, 45f, 51.5f, 55f), DepthReadings.robustWindow(listOf(47f, 45f, 53f, 55f)))
        assertEquals(listOf(3f, 4f, 5f, 6f), DepthReadings.robustWindow(listOf(4f, 4f, 4f, 6f)))
        assertNull(DepthReadings.robustWindow(listOf(4f, 6f, 6f, 6f)))
    }

    @Test
    fun `boite fine - la colonne centrale lit l'objet, pas le fond erode`() {
        // moitié haute fond 1.0, moitié basse objet 9.0 : la fenêtre érodée
        // (3 px de large, lignes 47..52) est moitié fond, la colonne centrale
        // (lignes 45..55) est majoritairement objet
        val values = FloatArray(100 * 100) { i -> if (i / 100 < 50) 1f else 9f }
        val map = DepthMap(100, 100, values)
        assertEquals(9f, DepthReadings.boxCloseness(map, listOf(47f, 45f, 53f, 55f), 100, 100)!!, 0.001f)
    }

    @Test
    fun `profondeur hors bornes - omise, pas bornee deguisee`() {
        // ancre : 2 m à 0,8 → échelle 1,6 ; la cible à 0,01 sortirait à 160 m
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val far = Detection("montagne", 0.9f, listOf(50f, 0f, 99f, 99f), 100, 100)
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box))
        val close = mapOf(person.box to 0.8f, far.box to 0.01f)
        val out = DepthReadings.refineWithCloseness(listOf(person, far), pinhole, close)
        assertEquals(1, out.size)
        assertEquals("personne", out.single().label)
    }

    @Test
    fun `statut - calibre avec ancres, non calibre sans`() {
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box))
        val ok = DepthReadings.refineStatusWithCloseness(listOf(person), pinhole, mapOf(person.box to 0.8f))
        assertTrue(ok.calibrated)
        assertEquals(2f, ok.readings.single().meters, 0.01f)
        val broken = DepthMap(100, 100, FloatArray(100 * 100) { Float.NaN })
        val ko = DepthReadings.refineWithStatus(100, 100, listOf(person), pinhole, broken)
        assertFalse(ko.calibrated)
        assertEquals(pinhole, ko.readings)
    }

    @Test
    fun `repli bas-au-contact - jamais une ancre`() {
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val biased = DistanceReading(7, "personne", 50f, 50f, person.box, 0.5f, true)
        val out = DepthReadings.refineStatusWithCloseness(listOf(person), listOf(biased), mapOf(person.box to 0.8f))
        assertFalse(out.calibrated)
        assertEquals(listOf(biased), out.readings)
        val honest = biased.copy(cutFallback = false)
        val ok = DepthReadings.refineStatusWithCloseness(listOf(person), listOf(honest), mapOf(person.box to 0.8f))
        assertTrue(ok.calibrated)
    }
}
