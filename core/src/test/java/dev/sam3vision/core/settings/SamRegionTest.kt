package dev.sam3vision.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mode US des Réglages : quelle URL SAM l'app appelle, et ce qui gagne sur quoi. */
class SamRegionTest {

    @Test
    fun `les presets sont reconnus et ne valent pas saisie manuelle`() {
        assertTrue(SamRegion.isPreset(SamRegion.Eu.presetBaseUrl))
        assertTrue(SamRegion.isPreset("  ${SamRegion.Us.presetBaseUrl}/  "))
        assertFalse(SamRegion.isPreset("https://mon-serveur.test/live"))
        assertFalse(SamRegion.isPreset(""))
    }

    @Test
    fun `par defaut l app vise l europe`() {
        val settings = Settings.Default
        assertEquals(SamRegion.Eu, settings.samRegion)
        assertEquals(SamRegion.Eu.presetBaseUrl, settings.resolveSamEndpoint())
    }

    @Test
    fun `le mode US bascule sur l endpoint americain`() {
        val settings = Settings.Default.copy(samRegion = SamRegion.Us)
        assertEquals(SamRegion.Us.presetBaseUrl, settings.resolveSamEndpoint())
    }

    @Test
    fun `une url saisie a la main gagne sur la region`() {
        val settings = Settings.Default.copy(
            samRegion = SamRegion.Us,
            samBaseUrl = "https://mon-serveur.test/live",
        )
        assertEquals("https://mon-serveur.test/live", settings.resolveSamEndpoint())
    }

    @Test
    fun `un preset herite ne fige plus la region`() {
        val settings = Settings.Default.copy(
            samRegion = SamRegion.Us,
            samBaseUrl = SamRegion.Eu.presetBaseUrl,
        )
        assertEquals(SamRegion.Us.presetBaseUrl, settings.resolveSamEndpoint())
    }

    @Test
    fun `l url injectee au build sert de repli par region`() {
        val eu = Settings.Default.resolveSamEndpoint(buildEu = "https://build.test/eu")
        assertEquals("https://build.test/eu", eu)
        val us = Settings.Default.copy(samRegion = SamRegion.Us)
            .resolveSamEndpoint(buildEu = "https://build.test/eu", buildUs = "https://build.test/us")
        assertEquals("https://build.test/us", us)
    }

    @Test
    fun `un nom persiste inconnu retombe sur l europe`() {
        assertEquals(SamRegion.Us, SamRegion.fromName("us"))
        assertEquals(SamRegion.Us, SamRegion.fromName("US"))
        assertEquals(SamRegion.Eu, SamRegion.fromName("mars"))
        assertEquals(SamRegion.Eu, SamRegion.fromName(null))
    }

    @Test
    fun `les deux presets sont distincts et non vides`() {
        assertTrue(SamRegion.Eu.presetBaseUrl.isNotBlank())
        assertTrue(SamRegion.Us.presetBaseUrl.isNotBlank())
        assertFalse(SamRegion.Eu.presetBaseUrl == SamRegion.Us.presetBaseUrl)
        assertTrue(SamRegion.Us.presetBaseUrl.contains("us-east"))
    }

    @Test
    fun `les presets sont pinces sur le workspace gaiamelie`() {
        assertTrue(SamRegion.Eu.presetBaseUrl.startsWith("https://gaiamelie--"))
        assertTrue(SamRegion.Eu.presetBaseUrl.contains("sam3-eu-a100"))
        assertTrue(SamRegion.Eu.presetBaseUrl.contains("eu-west"))
        assertTrue(SamRegion.Us.presetBaseUrl.startsWith("https://gaiamelie--"))
        assertTrue(SamRegion.Us.presetBaseUrl.contains("sam3-us-a100"))
        assertTrue(SamRegion.Us.presetBaseUrl.contains("us-east"))
    }

    @Test
    fun `le bail gpu tient en une ligne chiffree`() {
        assertTrue(SamRegion.GPU_LEASE_LINE.isNotBlank())
        assertTrue(SamRegion.GPU_LEASE_LINE.contains("25-38"))
        assertTrue(SamRegion.GPU_LEASE_LINE.contains("2-7"))
    }

    @Test
    fun `les presets retires restent des presets, pas une saisie manuelle`() {
        // Migration gaiamelie (03/11) : une URL baptiste-faisy persistée par une
        // ancienne version n'a jamais été un choix — l'app ne doit pas appeler
        // l'ancien workspace à vie.
        assertTrue(SamRegion.isPreset("https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run"))
        assertTrue(SamRegion.isPreset("  https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run/  "))
    }

    @Test
    fun `un preset retire herite ne fige ni la region ni l endpoint`() {
        val settings = Settings.Default.copy(
            samRegion = SamRegion.Us,
            samBaseUrl = "https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run",
        )
        assertEquals(SamRegion.Us.presetBaseUrl, settings.resolveSamEndpoint())
    }

    @Test
    fun `une url retiree injectee au build migre vers le preset de la region`() {
        // Régression vue le 04/11 : un APK cuit avec sam.base.url=baptiste-faisy
        // appelait l'ancien workspace (404 « invalid function call ») au lieu du
        // preset gaiamelie — la valeur injectée primait sur tout, sans migration.
        val eu = Settings.Default.resolveSamEndpoint(
            buildEu = "https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run",
        )
        assertEquals(SamRegion.Eu.presetBaseUrl, eu)
        val us = Settings.Default.copy(samRegion = SamRegion.Us).resolveSamEndpoint(
            buildUs = "https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run",
        )
        assertEquals(SamRegion.Us.presetBaseUrl, us)
    }
}
