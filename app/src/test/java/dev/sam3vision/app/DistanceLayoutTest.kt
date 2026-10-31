package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Placement des étiquettes de distance : même cadrage que l'aperçu, testé sans écran. */
class DistanceLayoutTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(1, label, meters, meters, box)

    private fun placed(labels: List<ScreenLabel>, widths: List<Float> = labels.map { 100f }, topInset: Float = 0f) =
        placePills(
            labels = labels,
            widths = widths,
            heights = labels.map { 30f },
            boxWidth = 1080f,
            boxHeight = 2400f,
            topInset = topInset,
        )

    @Test
    fun `cadrage crop remplit l'ecran et deborde en largeur`() {
        val transform = cropTransform(imageWidth = 384, imageHeight = 512, boxWidth = 1080f, boxHeight = 2400f)
        assertEquals(4.6875f, transform.scale, 0.001f)
        assertEquals(-360f, transform.offsetX, 0.01f)
        assertEquals(0f, transform.offsetY, 0.01f)
        // coin haut-gauche de l'image, hors cadre à gauche après crop
        assertEquals(-360f, transform.screenX(0f), 0.01f)
        assertEquals(540f, transform.screenX(192f), 0.01f)
    }

    @Test
    fun `etiquette ancree au coin haut-gauche de la boite`() {
        val layer = DistanceLayer(384, 512, listOf(reading("personne", 3.288f, listOf(100f, 100f, 160f, 300f))))
        val labels = screenLabels(layer, cropTransform(384, 512, 1080f, 2400f))
        assertEquals(1, labels.size)
        assertEquals("≈ 3.3 m", labels.first().text)
        // bord gauche de la boîte (100 px) → 100 × 4,6875 − 360 ; bord haut (100 px) → 468,75
        assertEquals(108.75f, labels.first().x, 0.5f)
        assertEquals(468.75f, labels.first().y, 0.5f)
        assertEquals(0, labels.first().colorIndex)
    }

    @Test
    fun `etiquette sur un bord reste dans le cadre`() {
        val layer = DistanceLayer(384, 512, listOf(reading("personne", 2f, listOf(0f, 100f, 0f, 300f))))
        assertEquals(0f, screenLabels(layer, cropTransform(384, 512, 1080f, 2400f)).first().x, 0.01f)
    }

    @Test
    fun `boite illisible ignoree et couche vide sans mesure`() {
        val broken = DistanceLayer(384, 512, listOf(reading("personne", 3f, listOf(1f, 2f))))
        assertTrue(screenLabels(broken, cropTransform(384, 512, 1080f, 2400f)).isEmpty())
        assertTrue(DistanceLayer(0, 0, listOf(reading("personne", 3f, listOf(0f, 0f, 1f, 1f)))).empty)
        assertTrue(DistanceLayer(384, 512).empty)
    }

    @Test
    fun `pastille posee au-dessus du coin de sa boite`() {
        // ancre (200, 500) ; pastille 100 × 30, fil de 22 px sous elle
        val pill = placed(listOf(ScreenLabel("≈ 3.2 m", x = 200f, y = 500f, colorIndex = 0))).single()
        assertEquals(200f, pill.left, 0.01f)
        assertEquals(448f, pill.top, 0.01f)
        assertEquals(200f, pill.anchorX, 0.01f)
        assertEquals(500f, pill.anchorY, 0.01f)
        // pastille au-dessus de la boîte : le fil la touche par le bas
        assertEquals(478f, pill.leadY(), 0.01f)
    }

    @Test
    fun `pastille collee au bord droit est ramenee dans le cadre`() {
        val pill = placed(listOf(ScreenLabel("≈ 1,2 m", x = 1000f, y = 500f, colorIndex = 0))).single()
        assertEquals(980f, pill.left, 0.01f)
    }

    @Test
    fun `pastille ne se cache pas sous le bandeau du haut`() {
        val pill = placed(listOf(ScreenLabel("≈ 4,0 m", x = 300f, y = 100f, colorIndex = 0)), topInset = 300f).single()
        assertEquals(300f, pill.top, 0.01f)
    }

    @Test
    fun `deux pastilles au meme endroit sont empilees vers le bas`() {
        val pills = placed(
            listOf(
                ScreenLabel("≈ 2,6 m", x = 300f, y = 900f, colorIndex = 0),
                ScreenLabel("≈ 2,5 m", x = 320f, y = 906f, colorIndex = 1),
            ),
        )
        assertEquals(848f, pills[0].top, 0.01f)
        assertEquals(900f, pills[0].anchorY, 0.01f)
        // la seconde descend sous la première (hauteur 30 + écart 8)
        assertEquals(892f, pills[1].top, 0.01f)
        // poussée sous sa boîte : le fil la touche par le haut
        assertEquals(892f, pills[1].leadY(), 0.01f)
    }

    @Test
    fun `deux pastilles eloignees ne bougent pas`() {
        val pills = placed(
            listOf(
                ScreenLabel("≈ 1,2 m", x = 200f, y = 400f, colorIndex = 0),
                ScreenLabel("≈ 3,0 m", x = 900f, y = 410f, colorIndex = 1),
            ),
        )
        assertEquals(348f, pills[0].top, 0.01f)
        assertEquals(358f, pills[1].top, 0.01f)
    }

    @Test
    fun `pastille plus large que le cadre reste collee a gauche`() {
        val pill = placed(
            listOf(ScreenLabel("personne ≈ 12 m", x = 500f, y = 400f, colorIndex = 0)),
            widths = listOf(2000f),
        ).single()
        assertEquals(0f, pill.left, 0.01f)
    }

    @Test
    fun `champ de vision saisi est tolere et borne`() {
        assertEquals(72.5f, parseFov("72.5"), 0.01f)
        assertEquals(0f, parseFov(""), 0f)
        assertEquals(0f, parseFov("0"), 0f)
        assertEquals(0f, parseFov("n'importe quoi"), 0f)
        assertEquals(15f, parseFov("2"), 0.01f)
        assertEquals(130f, parseFov("500"), 0.01f)
        assertEquals("67", fovText(67f))
        assertEquals("72.5", fovText(72.5f))
        assertEquals("", fovText(0f))
    }

    @Test
    fun `pastilles du bas portent la distance la plus courte`() {
        val measured = LiveUiState(
            distanceLayer = DistanceLayer(
                384,
                512,
                listOf(
                    reading("personne", 3.2f, listOf(0f, 0f, 10f, 20f)),
                    reading("chaise", 1.4f, listOf(0f, 0f, 10f, 20f)),
                    reading("personne", 2.1f, listOf(0f, 0f, 10f, 20f)),
                ),
            ),
        )
        assertEquals(listOf("personne 2.1 m", "chaise 1.4 m"), objectChips(measured))
    }

    @Test
    fun `sans distance, pastilles des concepts seuls`() {
        val frame = LiveFrame(
            width = 384,
            height = 512,
            elapsedMs = 40,
            objects = listOf(SegObject("personne", 0.9f, listOf(0f, 0f, 10f, 20f), ""), SegObject("chaise", 0.8f, listOf(0f, 0f, 10f, 20f), "")),
        )
        assertEquals(listOf("personne", "chaise"), objectChips(LiveUiState(frame = frame)))
        assertEquals(emptyList<String>(), objectChips(LiveUiState()))
    }

    @Test
    fun `ligne d'etat annonce l'objet le plus proche`() {
        val line = formatLiveStatus(true, 1.5f, 620, "A100", 3, nearestMeters = 1.44f)
        assertTrue(line.contains("1.4 m"))
        val without = formatLiveStatus(true, 1.5f, 620, "A100", 3)
        assertTrue(!without.contains("closest"))
    }

    @Test
    fun `pastille prefixee du concept pour les hotes sans masque`() {
        val lecture = reading("personne", 3.2f, listOf(100f, 100f, 160f, 300f))
        assertEquals("≈ 3.2 m", labelText(lecture, withLabel = false))
        assertEquals("personne ≈ 3.2 m", labelText(lecture, withLabel = true))
        assertEquals("objet ≈ 3.2 m", labelText(lecture.copy(label = "   "), withLabel = true))
    }
}
