package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteBaseUrlSeedTest {
    private val build = BuildDefaults(routeBaseUrl = "https://funnel-du-build.test")

    @Test
    fun `une installation neuve recoit l'URL du build`() {
        assertEquals("https://funnel-du-build.test", seedRouteBaseUrl(Settings.Default, build).routeBaseUrl)
    }

    @Test
    fun `une URL modifiee dans les parametres est conservee`() {
        val saved = Settings.Default.copy(routeBaseUrl = "https://mon-tunnel.test")
        assertEquals("https://mon-tunnel.test", seedRouteBaseUrl(saved, build).routeBaseUrl)
    }

    @Test
    fun `le chemin complet de preparation des reglages remplit aussi l'URL route`() {
        assertEquals("https://funnel-du-build.test", seeded(Settings.Default, build).routeBaseUrl)
    }
}
