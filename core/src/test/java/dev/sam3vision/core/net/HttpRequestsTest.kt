package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Une URL malformée (saisie en Réglages) ne plante jamais : chaque client
 * construit sa requête dans `executeBuilt` et renvoie une erreur typée.
 * Aucun appel réseau n'a lieu (le montage échoue avant l'envoi).
 */
class HttpRequestsTest {

    @Test
    fun `les sondes de sante refusent une base malformee sans lever`() = runBlocking {
        val sam = HttpSamClient("ht!tp://[::1").health()
        assertTrue(sam.exceptionOrNull() is ApiError.Network)
        val track = HttpTrackClient("pas une url").health()
        assertTrue(track.exceptionOrNull() is ApiError.Network)
        val video = HttpSam31VideoClient("::::").health()
        assertTrue(video.exceptionOrNull() is ApiError.Network)
    }

    @Test
    fun `les appels metiers refusent une base malformee sans lever`() = runBlocking {
        val close = HttpTrackClient("ht!tp://[::1").close("sess-1")
        assertTrue(close.exceptionOrNull() is ApiError.Network)
        val chat = HttpLlmClient("::::", apiKey = "cle", model = "modele")
            .describe("", emptyList())
        assertTrue(chat.exceptionOrNull() is ApiError.Network)
        val release = releaseSam("pas une url", OkHttpClient())
        assertTrue(release.exceptionOrNull() is ApiError.Network)
        val asr = MetaAsrClient("::::", apiKey = "cle").transcribe(ByteArray(100))
        assertTrue(asr.exceptionOrNull() is ApiError.Network)
    }

    @Test
    fun `le websocket refuse une URL malformee au lieu de planter`() {
        val transport = OkHttpWsTransport(OkHttpClient())
        assertFalse(transport.open("::::", onText = {}, onClosed = {}, onOpen = {}))
        assertFalse(transport.send("bonjour"))
    }
}
