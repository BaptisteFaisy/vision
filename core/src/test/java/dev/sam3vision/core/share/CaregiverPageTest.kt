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
    fun `embedded content keeps the received stream and drops the map key`() {
        val url = caregiverPageUrl("https://loved.example/?share=phone-b&key=old").toHttpUrl()
        assertEquals("loved.example", url.host)
        assertEquals("phone-b", url.queryParameter("share"))
        // The map no longer exists: an old link key never reaches the page.
        assertNull(url.queryParameter("key"))
        assertEquals("1", url.queryParameter("embedded"))
        assertEquals(CAREGIVER_ASSET_PREFIX + "index.html", url.encodedPath)
    }

    @Test
    fun `with no received link no implicit local stream is picked`() {
        val url = caregiverPageUrl("").toHttpUrl()
        assertFalse(url.queryParameterNames.contains("share"))
    }

    @Test
    fun `an old site link only keeps its id, on the relay`() {
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL + "?share=marie", relayShareLink("Marïe"))
        assertEquals(
            "marie",
            shareIdOf("https://suivi-parent.vercel.app/?share=marie&x=1"),
        )
        assertEquals("marie", shareIdOf("marie"))
        assertEquals("marie", shareIdOf("?share=marie"))
    }

    @Test
    fun `a link with no id or a bare url names no stream`() {
        assertNull(shareIdOf("https://example.com/"))
        assertNull(shareIdOf("javascript:alert(1)"))
        assertNull(shareIdOf("   "))
        assertNull(relayShareLink("not an id"))
    }

    @Test
    fun `publishing never leaves for the abandoned site`() {
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL, relayBaseOf("https://suivi-parent.vercel.app/?share=leo"))
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL, relayBaseOf("   "))
        assertEquals("https://relay.example/", relayBaseOf("https://relay.example/"))
        assertTrue(isLegacyRelayUrl("https://suivi-parent.vercel.app"))
        assertFalse(isLegacyRelayUrl("https://relay.example/"))
    }

    /**
     * On 29/10, the free `…freebuff.page` host answered 502 while the Worker
     * still responded: the caregiver page stayed on NO STREAM and the loved
     * one's camera no longer showed. An older link must repoint with no retype.
     */
    @Test
    fun `a link from the fallen free host keeps its id and repoints`() {
        val freebuff = "https://site-1607fe95049a481989380f019f9067f3.freebuff.page/?share=leo"
        assertTrue(isLegacyRelayUrl(freebuff))
        assertEquals(Settings.DEFAULT_PARENT_SITE_URL, relayBaseOf(freebuff))
        assertEquals("leo", shareIdOf(freebuff))
        assertFalse(isLegacyRelayUrl(Settings.DEFAULT_PARENT_SITE_URL))
    }

    /**
     * Regression guard: the app default is the Worker address itself, not a
     * pretty domain that may fall while the relay never moves a byte.
     */
    @Test
    fun `the default relay is the worker, not a middleman host`() {
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
    fun `the relay link is stable and always on the app base`() {
        assertEquals(
            "https://relay.example/?share=mum",
            relayShareLink("mum", "https://relay.example/"),
        )
        assertEquals(
            "https://relay.example/?share=mum",
            relayShareLink(" MUM ", "https://relay.example/?share=old"),
        )
    }

    @Test
    fun `only the ui assets are intercepted, never touching the relay`() {
        val page = caregiverPageUrl("https://loved.example/?share=phone-b")
        assertEquals("live.mjs", caregiverAssetName("https://loved.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://loved.example/api/state?share=phone-b", page))
        assertNull(caregiverAssetName("https://other.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://loved.example/.isee-caregiver/%2e%2e/secret", page))
    }
}
