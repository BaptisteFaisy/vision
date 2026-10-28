package dev.sam3vision.app

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
    fun `le lien porte l'identifiant normalise`() {
        assertEquals(
            "https://suivi-parent.vercel.app?share=leo",
            shareLink("https://suivi-parent.vercel.app/", "  Leo "),
        )
    }

    @Test
    fun `le lien vide retombe sur les defauts`() {
        assertTrue(shareLink("", "").contains("?share=leo"))
    }

    @Test
    fun `le lien deja parametre est conserve tel quel`() {
        val custom = "https://example.com/carte?share=maman"
        assertEquals(custom, shareLink(custom, "leo"))
    }
}
