package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.normalizeShareId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentShareTest {

    @Test
    fun `l'identifiant est nettoye en minuscules sans espaces ni accents`() {
        assertEquals("leo", normalizeShareId("  Léo "))
        assertEquals("leo", normalizeShareId("  Leo  "))
        assertEquals("", normalizeShareId("   "))
    }

    @Test
    fun `le lien porte l'identifiant normalise sur le relais`() {
        assertEquals(
            "https://relais.example/?share=leo",
            shareLink("https://relais.example/", "  Léo "),
        )
    }

    @Test
    fun `sans identifiant le lien ne devine aucun flux`() {
        val link = shareLink("", "")

        assertTrue(link.startsWith(Settings.DEFAULT_PARENT_SITE_URL.trimEnd('/')))
        assertTrue("aucun flux ne doit être deviné", !link.contains("share="))
    }

    @Test
    fun `une base deja parametree n impose pas son ancien flux`() {
        assertEquals(
            "https://relais.example/?share=leo",
            shareLink("https://relais.example/?share=ancien", "leo"),
        )
    }

    @Test
    fun `une base inutilisable ne renvoie jamais un lien casse`() {
        assertEquals(
            Settings.DEFAULT_PARENT_SITE_URL + "?share=leo",
            shareLink("javascript:alert(1)", "leo"),
        )
    }
}
