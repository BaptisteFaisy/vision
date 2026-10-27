package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Le lien de la page Caregiver : l'invitation collée gagne sur les champs historiques. */
class CaregiverShareLinkTest {

    @Test
    fun `sans invitation, site et identifiant enregistres`() {
        val settings = Settings.Default.copy(parentSiteUrl = "https://suivi-parent.vercel.app/", shareId = "leo")
        assertEquals("https://suivi-parent.vercel.app?share=leo", caregiverShareLink(settings))
    }

    @Test
    fun `lien complet colle = lien tel quel`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "https://suivi-parent.vercel.app?share=marie",
            shareId = "leo",
        )
        assertEquals("https://suivi-parent.vercel.app?share=marie", caregiverShareLink(settings))
    }

    @Test
    fun `code nu colle = identifiant sur le site enregistre`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "marie",
            parentSiteUrl = "https://suivi-parent.vercel.app/",
            shareId = "leo",
        )
        assertEquals("https://suivi-parent.vercel.app?share=marie", caregiverShareLink(settings))
    }

    @Test
    fun `site seul colle = site invite et identifiant enregistre`() {
        val settings = Settings.Default.copy(
            caregiverInvite = "https://suivi-parent.vercel.app/",
            shareId = "leo",
        )
        assertEquals("https://suivi-parent.vercel.app?share=leo", caregiverShareLink(settings))
    }
}
