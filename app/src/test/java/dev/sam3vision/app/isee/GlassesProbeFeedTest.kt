package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte du pont lunettes → sonde : parité stricte avec la caméra du téléphone
 * (ordre user du 04/11) — le flux vivant nourrit la sonde sans exiger ni
 * guidage ni Traversée ; seule la photo figée coupe le pont.
 */
class GlassesProbeFeedTest {

    @Test
    fun `flux vivant, le direct part sans guidage ni traversee`() {
        assertTrue(feed(live = true, streaming = true))
        assertTrue(feed(live = true, streaming = false))
        assertTrue(feed(live = false, streaming = true))
    }

    @Test
    fun `photo figee, le pont se tait meme avec flux`() {
        assertFalse(feed(live = true, streaming = true, photo = true))
        assertFalse(feed(live = false, streaming = true, photo = true))
    }

    @Test
    fun `sans flux, rien ne part`() {
        assertFalse(feed(live = false, streaming = false))
        assertFalse(feed(live = false, streaming = false, photo = true))
    }

    private fun feed(
        live: Boolean,
        streaming: Boolean,
        photo: Boolean = false,
    ): Boolean = glassesProbeFeed(
        glassesLive = live,
        glassesStreaming = streaming,
        photoDisplayed = photo,
    )
}
