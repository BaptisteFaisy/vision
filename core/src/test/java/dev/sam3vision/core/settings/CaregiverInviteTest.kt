package dev.sam3vision.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaregiverInviteTest {

    @Test
    fun `bare code means stream id`() {
        assertEquals(CaregiverInvite(siteUrl = "", shareId = "leo"), parseCaregiverInvite("leo"))
    }

    @Test
    fun `lone parameter means id with no site`() {
        assertEquals(CaregiverInvite(siteUrl = "", shareId = "marie"), parseCaregiverInvite("?share=marie"))
    }

    @Test
    fun `full link means site and id split`() {
        assertEquals(
            CaregiverInvite(
                siteUrl = "https://suivi-parent.vercel.app/",
                shareId = "leo",
            ),
            parseCaregiverInvite("https://suivi-parent.vercel.app/?share=leo"),
        )
    }

    @Test
    fun `extra parameters after the share are ignored`() {
        assertEquals(
            "paul",
            parseCaregiverInvite("https://suivi-parent.vercel.app/?share=paul&theme=dark")?.shareId,
        )
    }

    @Test
    fun `lone site with no parameter means site, id to come`() {
        assertEquals(
            CaregiverInvite(siteUrl = "https://suivi-parent.vercel.app/", shareId = ""),
            parseCaregiverInvite("https://suivi-parent.vercel.app/"),
        )
    }

    @Test
    fun `empty or blank means no invite`() {
        assertNull(parseCaregiverInvite(""))
        assertNull(parseCaregiverInvite("   "))
    }

    @Test
    fun `surrounding spaces are forgiven`() {
        assertEquals(
            CaregiverInvite(siteUrl = "", shareId = "leo"),
            parseCaregiverInvite("  leo  "),
        )
    }
}
