package dev.sam3vision.app.isee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte du pont lunettes → sonde : pas d'envoi automatique dès la connexion —
 * seulement sur demande explicite (guidage ou Traversée). Ordre user du 03/11 :
 * fini les photos automatiques toutes les X secondes quand les lunettes sont portées.
 */
class GlassesProbeFeedTest {

    @Test
    fun `lunettes connectees au repos, rien ne part`() {
        assertFalse(feed(live = true, streaming = true))
        assertFalse(feed(live = true, streaming = false))
        assertFalse(feed(live = false, streaming = true))
    }

    @Test
    fun `guidage, le direct part`() {
        assertTrue(feed(live = true, streaming = true, guidance = true))
        assertTrue(feed(live = false, streaming = true, guidance = true))
    }

    @Test
    fun `traversee, le direct part meme sans guidage`() {
        assertTrue(feed(live = true, streaming = false, crossing = true))
        assertTrue(feed(live = false, streaming = true, crossing = true))
    }

    @Test
    fun `photo figee, le pont se tait meme en guidage ou traversee`() {
        assertFalse(feed(live = true, streaming = true, photo = true, guidance = true))
        assertFalse(feed(live = true, streaming = true, photo = true, crossing = true))
    }

    @Test
    fun `sans flux, rien ne part meme en guidage et traversee`() {
        assertFalse(feed(live = false, streaming = false, guidance = true, crossing = true))
    }

    private fun feed(
        live: Boolean,
        streaming: Boolean,
        photo: Boolean = false,
        guidance: Boolean = false,
        crossing: Boolean = false,
    ): Boolean = glassesProbeFeed(
        glassesLive = live,
        glassesStreaming = streaming,
        photoDisplayed = photo,
        guidance = guidance,
        crossingActive = crossing,
    )
}
