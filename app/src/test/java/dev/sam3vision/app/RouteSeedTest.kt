package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSeedTest {
    private val build = BuildDefaults(routeBaseUrl = "https://route.du-build")

    @Test
    fun `une installation neuve et une ancienne installation recoivent l'endpoint du build`() {
        assertEquals("https://route.du-build", seedRouteBaseUrl(Settings.Default, build).routeBaseUrl)
    }

    @Test
    fun `un endpoint saisi dans les reglages est conserve`() {
        val saved = Settings.Default.copy(routeBaseUrl = "https://mon-tunnel")
        assertEquals("https://mon-tunnel", seedRouteBaseUrl(saved, build).routeBaseUrl)
    }

    @Test
    fun `le chemin complet de preparation des reglages remplit aussi l'endpoint`() {
        assertEquals("https://route.du-build", seeded(Settings.Default, build).routeBaseUrl)
    }

    @Test
    fun `l'ancien defaut sur le workspace desactive reprend l'endpoint du build`() {
        val old = "https://baptiste-faisy--valhalla-idf-cpu-valhallarouter-web.us-east.modal.run"
        for (saved in listOf(old, "$old/", " $old ")) {
            val loaded = Settings.Default.copy(routeBaseUrl = saved)
            assertEquals("https://route.du-build", seeded(loaded, build).routeBaseUrl)
        }
    }

    @Test
    fun `un endpoint saisi hors du workspace desactive survit a toute la preparation`() {
        val saved = Settings.Default.copy(routeBaseUrl = "https://mon-tunnel")
        assertEquals("https://mon-tunnel", seeded(saved, build).routeBaseUrl)
    }
}
