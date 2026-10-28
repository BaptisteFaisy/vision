package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le lien de la page Caregiver : l'identifiant reçu décide du flux, et le lien est
 * toujours reconstruit sur le relais de l'app (le site Vercel ne sert plus la carte).
 */
class CaregiverShareLinkTest {

    private val relay = Settings.DEFAULT_PARENT_SITE_URL.trimEnd('/')

    @Test
    fun `sans invitation aucun suivi local ne se substitue au proche`() {
        val settings = Settings.Default.copy(parentSiteUrl = relay, shareId = "leo")
        assertEquals("", caregiverShareLink(settings))
    }

    @Test
    fun `un ancien lien du site ne garde que son identifiant`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "https://suivi-parent.vercel.app?share=marie",
            shareId = "leo",
        )
        assertEquals("$relay/?share=marie", caregiverShareLink(settings))
    }

    @Test
    fun `code nu colle = identifiant sur le relais`() {
        val settings = Settings.Default.copy(caregiverInvite = "marie", shareId = "leo")
        assertEquals("$relay/?share=marie", caregiverShareLink(settings))
    }

    @Test
    fun `site seul colle ne reprend jamais l identifiant local`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "https://suivi-parent.vercel.app/",
            shareId = "leo",
        )
        assertEquals("", caregiverShareLink(settings))
    }

    @Test
    fun `ancien lien recu dans le site est repris par son identifiant`() {
        val legacy = "https://suivi-parent.vercel.app/?share=proche-distant"
        assertEquals("$relay/?share=proche-distant", caregiverShareLink(Settings.Default.copy(parentSiteUrl = legacy)))
    }

    @Test
    fun `code recu remplace l ancienne session`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "marie",
            parentSiteUrl = "$relay/?share=ancien",
        )
        assertEquals("$relay/?share=marie", caregiverShareLink(settings))
    }
}
