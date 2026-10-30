package dev.sam3vision.app

import dev.sam3vision.core.glasses.CaptureAction
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Anti-régression : une photo prise avec les lunettes ne pousse JAMAIS de
 * route — elle s'affiche sur l'accueil iSee (personne accompagnée). Avant,
 * PHOTO partait sur l'écran Analyse et ASK sur le Chat, vidant l'écran de la
 * personne accompagnée au moment où elle prend sa photo.
 */
class GlassesPhotoRoutingTest {

    @Test
    fun `photo lunettes PHOTO — aucune navigation`() {
        assertNull(routeForGlassesPhoto(CaptureAction.PHOTO))
    }

    @Test
    fun `photo lunettes ASK — aucune navigation`() {
        assertNull(routeForGlassesPhoto(CaptureAction.ASK))
    }

    @Test
    fun `photo lunettes LIVE — aucune navigation`() {
        assertNull(routeForGlassesPhoto(CaptureAction.LIVE))
    }
}
