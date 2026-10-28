package dev.sam3vision.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEndpointTest {

    @Test
    fun `la saisie manuelle gagne sur le defaut de build`() {
        val settings = Settings.Default.copy(routeBaseUrl = "https://manuelle.test/")

        assertEquals("https://manuelle.test/", settings.resolveRouteEndpoint("https://build.test"))
    }

    @Test
    fun `sans saisie le defaut de build est utilise`() {
        assertEquals("https://build.test", Settings.Default.resolveRouteEndpoint("https://build.test"))
    }

    @Test
    fun `sans saisie ni build le routage in-app est desactive`() {
        assertEquals("", Settings.Default.resolveRouteEndpoint())
        assertEquals("", Settings.Default.resolveRouteEndpoint("  "))
        assertFalse(Settings.Default.routeConfigured)
    }

    @Test
    fun `une saisie blanche retombe sur le defaut de build`() {
        val settings = Settings.Default.copy(routeBaseUrl = "   ")

        assertEquals("https://build.test", settings.resolveRouteEndpoint("https://build.test"))
    }

    @Test
    fun `routeConfigured est vrai des qu un endpoint existe`() {
        assertTrue(Settings.Default.copy(routeBaseUrl = "https://manuelle.test").routeConfigured)
    }
}
