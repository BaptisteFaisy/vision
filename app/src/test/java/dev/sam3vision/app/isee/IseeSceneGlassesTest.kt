package dev.sam3vision.app.isee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les couches de scène quand les lunettes montrent leur direct : la rue s'ouvre sans capture
 * téléphone, et c'est le flux des lunettes qui l'occupe (le pane préfère `glassesFeed` à la
 * caméra du téléphone quand les deux sont vrais).
 */
class IseeSceneGlassesTest {

    @Test
    fun `le direct lunettes ouvre la rue sans capture`() {
        val layers = iseeSceneLayers(bubbles = 1, dissolving = false, captureLive = false, glassesLive = true)
        assertTrue(layers.glassesFeed)
        assertFalse(layers.cameraCard)
        assertFalse(layers.pageDots)
    }

    @Test
    fun `sans lunettes, les couches sont celles d avant`() {
        val layers = iseeSceneLayers(bubbles = 2, dissolving = false, captureLive = false)
        assertFalse(layers.glassesFeed)
        assertTrue(layers.pageDots)
    }

    @Test
    fun `capture telephone et direct lunettes, les deux couches vivent`() {
        // La rue ne montre qu'une source : le pane choisit les lunettes (voir PortraitCamera).
        val layers = iseeSceneLayers(bubbles = 2, dissolving = false, captureLive = true, glassesLive = true)
        assertTrue(layers.cameraCard)
        assertTrue(layers.glassesFeed)
        assertFalse(layers.pageDots)
    }

    @Test
    fun `flux sans image, la rue existe en fantome sans capture`() {
        val layers = ghostLayers(captureLive = false)
        assertTrue(layers.glassesGhost)
        assertFalse(layers.glassesFeed)
        assertFalse(layers.cameraCard)
    }

    @Test
    fun `fantome meme pendant une capture telephone`() {
        // L'enregistrement continue, mais la rue se prépare pour les lunettes : sinon, avec
        // une capture active, le direct ne démarrerait jamais (impasse constatée sur téléphone).
        val layers = ghostLayers(captureLive = true)
        assertTrue(layers.glassesGhost)
        assertTrue(layers.cameraCard)
    }

    @Test
    fun `pas de fantome une fois la premiere image rendue`() {
        val layers = iseeSceneLayers(
            bubbles = 1, dissolving = false, captureLive = false, glassesLive = true, glassesStreaming = true,
        )
        assertFalse(layers.glassesGhost)
        assertTrue(layers.glassesFeed)
    }

    @Test
    fun `la rue montre les lunettes qui vivent, naissent, ou s eteignent`() {
        assertTrue(showGlassesFeed(live = true, ghost = false, cameraCard = false, open = 1f, lastGlasses = false))
        assertTrue(showGlassesFeed(live = false, ghost = true, cameraCard = false, open = 0f, lastGlasses = false))
        // Les lunettes prennent la rue même si le téléphone filme (l'enregistrement continue).
        assertTrue(showGlassesFeed(live = true, ghost = false, cameraCard = true, open = 1f, lastGlasses = false))
        assertTrue(showGlassesFeed(live = false, ghost = true, cameraCard = true, open = 1f, lastGlasses = false))
        // Fin du direct en pleine fermeture : dernière image figée le temps du fondu.
        assertTrue(showGlassesFeed(live = false, ghost = false, cameraCard = false, open = 0.5f, lastGlasses = true))
    }

    @Test
    fun `la rue rend le telephone au repos, ferme, ou qui filme`() {
        assertFalse(showGlassesFeed(live = false, ghost = false, cameraCard = false, open = 0f, lastGlasses = false))
        // Le téléphone qui filme reprend la rue dès que les lunettes ne montrent plus rien.
        assertFalse(showGlassesFeed(live = false, ghost = false, cameraCard = true, open = 1f, lastGlasses = true))
        assertFalse(showGlassesFeed(live = false, ghost = false, cameraCard = false, open = 0f, lastGlasses = true))
    }

    private fun ghostLayers(captureLive: Boolean) = iseeSceneLayers(
        bubbles = 1, dissolving = false, captureLive = captureLive, glassesLive = false, glassesStreaming = true,
    )
}
