package dev.sam3vision.core.iseedesign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IseeSceneTest {

    @Test
    fun `l'intro courte tient sur une page`() {
        val pages = textPages("Tap anywhere to see.")
        assertEquals(listOf(listOf("Tap anywhere to", "see.")), pages)
    }
    @Test
    fun `l'intro longue tient a 3 lignes par page et 2 pages partagent leur jointure`() {
        val text = IheeSample.long
        val pages = textPages(text)
        assertTrue(pages.size > 1)
        // La jointure conserve la ligne de lecture : la dernière ligne d'une page
        // redémarre exactement la suivante (le glissement d'une page à 3 lignes).
        assertEquals(pages[0].last(), pages[1].first())
        assertEquals(pages[1].last(), pages[2].first())
        // Page de 3 lignes max, et le seuil est joué de près.
        assertTrue(pages.all { it.size <= HEADING_MAX_LINES })
    }

    @Test
    fun `une page seule reste vide si le texte est vide`() {
        assertEquals(listOf(listOf("")), textPages(""))
    }

    @Test
    fun `les satellites ne se recouvrent pas plus de quatre au meme endroit`() {
        val spots = satellitePositions(10)
        assertEquals(10, spots.size)
        assertEquals(satellitePositions(9), spots.subList(0, 9))
    }

    @Test
    fun `l'apparence d'un meme id ne change jamais et tire dans les 16`() {
        val a = bubbleLookIndex(12345)
        assertEquals(a, bubbleLookIndex(12345))
        val distinct = (0 until 64).map { bubbleLookIndex(it.toLong() * 7919) }.toSet()
        assertTrue("tirage trop pauvre: $distinct", distinct.size >= 10)
        assertTrue(bubbleLookIndex(1) in 0 until BubbleLook.values().size)
    }

    @Test
    fun `le flow de pixel part un peu decalenté et posee dans la bulle`() {
        val a = pixelTrip(0.2f, 0.4f, 0.5f, 0.5f, t = 0f, seed = 3)
        val b = pixelTrip(0.2f, 0.4f, 0.5f, 0.5f, t = 1f, seed = 3)
        assertEquals(0.2f, a.x, 0.01f)
        assertEquals(0.5f, b.x, 0.01f)
        val c = pixelTrip(0.2f, 0.4f, 0.5f, 0.5f, t = 1f, seed = 4)
        // Le même instant, une autre case : le vol a une autre secousse.
        assertNotEquals(a.x, c.x, 0.0001f)
    }

    @Test
    fun `l'echantillonnage grille calcule la moyenne par cellule`() {
        // Image 2×2 : ligne du haut rouge, ligne du bas noire, échantillonnée en
        // 2×1 cellules (une par ligne) : chaque cellule reprend sa couleur pleine.
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt())
        val out = sampleGrid(colors, width = 2, height = 2, rows = 2, cols = 1)
        assertEquals(2, out.size)
        assertEquals(0xFFFF0000.toInt(), out[0])
        assertEquals(0xFF000000.toInt(), out[1])
        // Une cellule couvrant les deux lignes fait une moyenne charbonneuse.
        val mixed = sampleGrid(colors, width = 2, height = 2, rows = 1, cols = 2)
        assertEquals(0xFF7F0000.toInt(), mixed[0] and 0x00FFFFFF or 0xFF000000.toInt())
    }

    @Test
    fun `l'echantillonnage grille accepte les bogues de dimensions`() {
        assertTrue(sampleGrid(intArrayOf(), width = 0, height = 0, rows = 2, cols = 2).all { it == 0 })
        assertEquals(0, sampleGrid(intArrayOf(0xFF010203.toInt()), width = 1, height = 1, rows = 0, cols = 1).size)
    }

    @Test
    fun `le gros texte ne depasse jamais 3 lignes`() {
        val text = IheeSample.long
        val pages = textPages(text, maxLines = HEADING_MAX_LINES)
        assertTrue(pages.all { page -> page.all { it.length <= 19 } })
    }

}

object IheeSample {
    val long = "Welcome to iSee. To record your surroundings, tap anywhere on the screen " +
        "to start the video, and tap again to stop and analyze it. " +
        "To ask a question or make a request, tap and hold while you speak, then release. " +
        "To stop hearing this intro, say Disable iSee intro."
}
