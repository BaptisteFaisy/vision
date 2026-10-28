package dev.sam3vision.core.share

import dev.sam3vision.core.settings.Settings
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CaregiverPageTest {
    @Test
    fun `le contenu embarque conserve le flux recu et active la cle des reglages`() {
        val url = caregiverPageUrl("https://proche.example/?share=telephone-b&key=ancienne", "cle-carte").toHttpUrl()
        assertEquals("proche.example", url.host)
        assertEquals("telephone-b", url.queryParameter("share"))
        assertEquals("cle-carte", url.queryParameter("key"))
        assertEquals("1", url.queryParameter("embedded"))
        assertEquals(CAREGIVER_ASSET_PREFIX + "index.html", url.encodedPath)
    }

    @Test
    fun `sans lien recu aucun flux local implicite n est selectionne`() {
        val url = caregiverPageUrl("", "cle-carte").toHttpUrl()
        assertFalse(url.queryParameterNames.contains("share"))
    }

    @Test
    fun `un ancien lien du site ne garde que son identifiant, sur le relais`() {
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL + "?share=marie", relayShareLink("Marïe"))
        assertEquals(
            "marie",
            shareIdOf("https://suivi-parent.vercel.app/?share=marie&x=1"),
        )
        assertEquals("marie", shareIdOf("marie"))
        assertEquals("marie", shareIdOf("?share=marie"))
    }

    @Test
    fun `un lien sans identifiant ou une url ne designe aucun flux`() {
        assertNull(shareIdOf("https://example.com/"))
        assertNull(shareIdOf("javascript:alert(1)"))
        assertNull(shareIdOf("   "))
        assertNull(relayShareLink("pas un identifiant"))
    }

    @Test
    fun `publier ne repart jamais vers le site abandonne`() {
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL, relayBaseOf("https://suivi-parent.vercel.app/?share=leo"))
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL, relayBaseOf("   "))
        assertEquals("https://relais.example/", relayBaseOf("https://relais.example/"))
        assertTrue(isLegacyRelayUrl("https://suivi-parent.vercel.app"))
        assertFalse(isLegacyRelayUrl("https://relais.example/"))
    }

    @Test
    fun `le lien du relais est stable et toujours sur la base de l app`() {
        assertEquals(
            "https://relais.example/?share=maman",
            relayShareLink("maman", "https://relais.example/"),
        )
        assertEquals(
            "https://relais.example/?share=maman",
            relayShareLink(" MAMAN ", "https://relais.example/?share=ancien"),
        )
    }

    @Test
    fun `seuls les assets de l interface sont interceptes sans toucher au relais`() {
        val page = caregiverPageUrl("https://proche.example/?share=telephone-b", "cle-carte")
        assertEquals("live.mjs", caregiverAssetName("https://proche.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://proche.example/api/state?share=telephone-b", page))
        assertNull(caregiverAssetName("https://autre.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://proche.example/.isee-caregiver/%2e%2e/secret", page))
    }
}
