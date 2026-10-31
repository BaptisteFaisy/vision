package dev.sam3vision.app.voicenav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Rotation de la boule de chargement : un tour par 800 ms. */
class RouteLoadingSpinTest {

    @Test
    fun `angle zero au depart`() {
        assertEquals(0f, spinAngle(0f), 0.001f)
    }

    @Test
    fun `demi-tour a mi-periode`() {
        assertEquals(180f, spinAngle(0.4f), 0.001f)
    }

    @Test
    fun `retour a zero au tour complet`() {
        assertEquals(0f, spinAngle(0.8f), 0.001f)
    }
}
