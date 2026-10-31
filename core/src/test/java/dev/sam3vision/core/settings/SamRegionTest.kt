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
    fun `le bail gpu tient en une ligne chiffree`() {
        assertTrue(SamRegion.GPU_LEASE_LINE.isNotBlank())
        assertTrue(SamRegion.GPU_LEASE_LINE.contains("25-38"))
        assertTrue(SamRegion.GPU_LEASE_LINE.contains("2-7"))
    }
}
