package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Caregiver page link: the received id picks the stream, and the link is
 * always rebuilt on the app relay (the Vercel site no longer serves the map).
 */
class CaregiverShareLinkTest {

    private val relay = Settings.DEFAULT_PARENT_SITE_URL.trimEnd('/')

    @Test
    fun `no invite means no local tracking stands in for the loved one`() {
        val settings = Settings.Default.copy(parentSiteUrl = relay, shareId = "leo")
        assertEquals("", caregiverShareLink(settings))
    }

    @Test
    fun `an old site link only keeps its id`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "https://suivi-parent.vercel.app?share=marie",
            shareId = "leo",
        )
        assertEquals("$relay/?share=marie", caregiverShareLink(settings))
    }

    @Test
    fun `pasted bare code means id on the relay`() {
        val settings = Settings.Default.copy(caregiverInvite = "marie", shareId = "leo")
        assertEquals("$relay/?share=marie", caregiverShareLink(settings))
    }

    @Test
    fun `pasted lone site never resumes the local id`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "https://suivi-parent.vercel.app/",
            shareId = "leo",
        )
        assertEquals("", caregiverShareLink(settings))
    }

    @Test
    fun `old link received in the site is resumed by its id`() {
        val legacy = "https://suivi-parent.vercel.app/?share=distant-loved"
        assertEquals("$relay/?share=distant-loved", caregiverShareLink(Settings.Default.copy(parentSiteUrl = legacy)))
    }

    @Test
    fun `received code replaces the old session`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "marie",
            parentSiteUrl = "$relay/?share=old",
        )
        assertEquals("$relay/?share=marie", caregiverShareLink(settings))
    }
}
