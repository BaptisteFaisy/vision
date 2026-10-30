package dev.sam3vision.app.isee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La photo des lunettes occupe la rue (emplacement caméra) : elle ouvre la rue
 * sans capture téléphone ni direct, et masque la pagination comme tout contenu
 * de rue. Sans photo, les couches sont celles d'avant.
 */
class IseeScenePhotoTest {

    @Test
    fun `photo affichee — la rue s ouvre sans capture ni direct`() {
        val layers = iseeSceneLayers(bubbles = 1, dissolving = false, captureLive = false, photoDisplayed = true)

        assertTrue(layers.photoFeed)
        assertFalse(layers.cameraCard)
        assertFalse(layers.pageDots)
    }

    @Test
    fun `photo affichee pendant une capture — les deux couches vivent, la photo gagne la rue`() {
        val layers = iseeSceneLayers(bubbles = 2, dissolving = false, captureLive = true, photoDisplayed = true)

        assertTrue(layers.photoFeed)
        assertTrue(layers.cameraCard)
        assertFalse(layers.pageDots)
    }

    @Test
    fun `sans photo — pas de couche photo, pagination inchangee`() {
        val layers = iseeSceneLayers(bubbles = 2, dissolving = false, captureLive = false, photoDisplayed = false)

        assertFalse(layers.photoFeed)
        assertTrue(layers.pageDots)
    }
}
