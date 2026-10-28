package dev.sam3vision.core.share

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `seuls les assets de l interface sont interceptes sans toucher au relais`() {
        val page = caregiverPageUrl("https://proche.example/?share=telephone-b", "cle-carte")
        assertEquals("live.mjs", caregiverAssetName("https://proche.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://proche.example/api/state?share=telephone-b", page))
        assertNull(caregiverAssetName("https://autre.example/.isee-caregiver/live.mjs", page))
        assertNull(caregiverAssetName("https://proche.example/.isee-caregiver/%2e%2e/secret", page))
    }
}
