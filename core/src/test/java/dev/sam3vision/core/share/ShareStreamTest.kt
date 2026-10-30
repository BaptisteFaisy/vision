package dev.sam3vision.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareStreamTest {

    @Test
    fun `le direct vise la salle du flux, sur la meme origine que le relais`() {
        assertEquals(
            "wss://relais.example/ws?share=leo&role=push&key=cle-1",
            streamUrlOf("https://relais.example/", "leo", "cle-1"),
        )
        // Un relais local (http) reste en clair : sinon le direct ne marcherait
        // pas en développement, et la page n'a aucun moyen de le deviner.
        assertEquals(
            "ws://127.0.0.1:8836/ws?share=leo&role=push&key=",
            streamUrlOf("http://127.0.0.1:8836/?share=ancien", "leo", ""),
        )
    }

    @Test
    fun `l identifiant est normalise et une base illisible ne produit aucun direct`() {
        assertEquals(
            "wss://relais.example/ws?share=leo&role=push&key=k",
            streamUrlOf("https://relais.example/", "Léo", "k"),
        )
        assertNull(streamUrlOf("pas un lien", "leo", "k"))
        // Un identifiant impossible ne doit jamais retomber sur le flux local.
        assertNull(streamUrlOf("https://relais.example/", "pas un identifiant", "k"))
        assertNull(streamUrlOf("https://relais.example/", "", "k"))
    }

    @Test
    fun `le telephone n emet que si un aidant regarde`() {
        assertEquals(0, audienceCountOf("""{"type":"audience","watch":0}"""))
        assertEquals(2, audienceCountOf("""{"type":"audience","watch":2}"""))
        assertEquals(true, audienceWatching("""{"type":"audience","watch":1}"""))
        assertEquals(false, audienceWatching("""{"type":"audience","watch":0}"""))
    }

    @Test
    fun `l annonce camera a la forme de l avis d audience`() {
        assertEquals("""{"type":"camera","on":true}""", cameraNotice(true))
        assertEquals("""{"type":"camera","on":false}""", cameraNotice(false))
    }

    @Test
    fun `un message qui n est pas un avis d audience ne fait rien`() {
        assertNull(audienceCountOf("bonjour"))
        assertNull(audienceCountOf(""))
        assertNull(audienceWatching("{}"))
        // Pas de faux positif sur une frame binaire décodée en texte.
        assertNull(audienceCountOf("\u0000\u0001watch"))
    }
}
