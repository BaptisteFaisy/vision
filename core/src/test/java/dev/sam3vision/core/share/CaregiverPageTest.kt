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
    fun `le contenu embarque conserve le flux recu et oublie la cle de carte`() {
        val url = caregiverPageUrl("https://proche.example/?share=telephone-b&key=ancienne").toHttpUrl()
        assertEquals("proche.example", url.host)
        assertEquals("telephone-b", url.queryParameter("share"))
        // La carte n'existe plus : la clé d'un ancien lien ne part pas à la page.
        assertNull(url.queryParameter("key"))
        assertEquals("1", url.queryParameter("embedded"))
        assertEquals(CAREGIVER_ASSET_PREFIX + "index.html", url.encodedPath)
    }

    @Test
    fun `sans lien recu aucun flux local implicite n est selectionne`() {
        val url = caregiverPageUrl("").toHttpUrl()
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

    /**
     * Le 29/10, l'hôte gratuit `…freebuff.page` a rendu 502 alors que le Worker
     * répondait encore : la page caregiver restait en NO STREAM et la caméra du
     * proche ne s'affichait plus. Un lien d'avant doit repointer sans être ressaisi.
     */
    @Test
    fun `un lien de l hebergement gratuit tombe garde son identifiant et repointe`() {
        val freebuff = "https://site-1607fe95049a481989380f019f9067f3.freebuff.page/?share=leo"
        assertTrue(isLegacyRelayUrl(freebuff))
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL, relayBaseOf(freebuff))
        assertEquals("leo", shareIdOf(freebuff))
        assertFalse(isLegacyRelayUrl(Settings.DEFAULT_PARENT_SITE_URL))
    }

    /**
     * Garde de régression : le défaut de l'app est l'adresse du Worker lui-même, pas
     * un domaine d'apparence qui peut tomber sans que le relais bouge d'un octet.
     */
    @Test
    fun `le relais par defaut est le worker et non un hebergement intermediaire`() {
        val url = Settings.DEFAULT_PARENT_SITE_URL.toHttpUrl()
        assertEquals("isee-suivi.duello.workers.dev", url.host)
        assertEquals("https", url.scheme)
        assertNull(url.queryParameter("share"))
        assertEquals(
            "https://isee-suivi.duello.workers.dev/?share=marie",
            relayShareLink("marie"),
        )
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
        val page = caregiverPageUrl("https://proche.example/?share=telephone-b")
        assertEquals("live.mjs", caregiverAssetName("https://proche.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://proche.example/api/state?share=telephone-b", page))
        assertNull(caregiverAssetName("https://autre.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://proche.example/.isee-caregiver/%2e%2e/secret", page))
    }
}
