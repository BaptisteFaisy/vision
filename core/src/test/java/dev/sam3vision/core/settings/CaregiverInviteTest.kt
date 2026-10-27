package dev.sam3vision.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaregiverInviteTest {

    @Test
    fun `code nu = identifiant du flux`() {
        assertEquals(CaregiverInvite(siteUrl = "", shareId = "leo"), parseCaregiverInvite("leo"))
    }

    @Test
    fun `parametre seul = identifiant sans site`() {
        assertEquals(CaregiverInvite(siteUrl = "", shareId = "marie"), parseCaregiverInvite("?share=marie"))
    }

    @Test
    fun `lien complet = site et identifiant separes`() {
        assertEquals(
            CaregiverInvite(
                siteUrl = "https://suivi-parent.vercel.app/",
                shareId = "leo",
            ),
            parseCaregiverInvite("https://suivi-parent.vercel.app/?share=leo"),
        )
    }

    @Test
    fun `parametres supplementaires apres le partage ignores`() {
        assertEquals(
            "paul",
            parseCaregiverInvite("https://suivi-parent.vercel.app/?share=paul&theme=sombre")?.shareId,
        )
    }

    @Test
    fun `site seul sans parametre = site, identifiant a venir`() {
        assertEquals(
            CaregiverInvite(siteUrl = "https://suivi-parent.vercel.app/", shareId = ""),
            parseCaregiverInvite("https://suivi-parent.vercel.app/"),
        )
    }

    @Test
    fun `vide ou espaces = pas d invitation`() {
        assertNull(parseCaregiverInvite(""))
        assertNull(parseCaregiverInvite("   "))
    }

    @Test
    fun `espaces autour pardonnes`() {
        assertEquals(
            CaregiverInvite(siteUrl = "", shareId = "leo"),
            parseCaregiverInvite("  leo  "),
        )
    }
}
