package dev.sam3vision.core.net

import dev.sam3vision.core.model.RoutePoint
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Une URL malformée (saisie en Réglages) ne plante jamais : chaque client
 * refuse en local avec une erreur typée au message actionnable (le jargon du
 * parseur OkHttp ne remonte plus à l'écran). Aucun appel réseau n'a lieu.
 */
class HttpRequestsTest {

    @Test
    fun `les sondes de sante refusent une base malformee sans lever`() = runBlocking {
        assertActionableNetwork(HttpSamClient("ht!tp://[::1").health())
        assertActionableNetwork(HttpTrackClient("pas une url").health())
        assertActionableNetwork(HttpSam31VideoClient("::::").health())
    }

    @Test
    fun `les appels metiers refusent une base malformee sans lever`() = runBlocking {
        assertActionableNetwork(HttpTrackClient("ht!tp://[::1").close("sess-1"))
        assertActionableNetwork(
            HttpLlmClient("::::", apiKey = "cle", model = "modele").describe("", emptyList()),
        )
        assertActionableNetwork(releaseSam("pas une url", OkHttpClient()))
        assertActionableNetwork(MetaAsrClient("::::", apiKey = "cle").transcribe(ByteArray(100)))
    }

    @Test
    fun `les appels Meta refusent une base malformee avec un message actionnable`() = runBlocking {
        assertActionableNetwork(MetaAsrClient("api.meta.ai/v1", apiKey = "cle").transcribe(ByteArray(100)))
        assertActionableNetwork(MetaImageClient("::::", apiKey = "cle").editToPictogram(ByteArray(10), "porte"))
    }

    @Test
    fun `toutes les routes SAM refusent une base malformee avec un message actionnable`() = runBlocking {
        assertActionableNetwork(HttpSamClient("::::").segment("QQ==", null))
        assertActionableNetwork(HttpLiveClient("::::").live("QQ==", null, 640, 4))
        assertActionableNetwork(HttpLiveClient("::::").health())
        assertActionableNetwork(HttpTrackClient("::::").start("QQ==", listOf("personne")))
        assertActionableNetwork(HttpTrackClient("::::").frame("sess-1", "QQ=="))
    }

    @Test
    fun `l envoi video refuse une base malformee en BadResponse actionnable`() = runBlocking {
        val video = Files.createTempFile("endpoint-video", ".mp4").toFile().apply {
            writeBytes(ByteArray(64) { it.toByte() })
        }
        try {
            val failure = HttpSam31VideoClient("::::").sendVideo(video, null).exceptionOrNull()
            assertTrue("attendu BadResponse (contrat historique), obtenu $failure", failure is ApiError.BadResponse)
            assertTrue(
                "message actionnable attendu, obtenu ${failure?.message}",
                failure?.message?.contains("Settings") == true,
            )
        } finally {
            video.delete()
        }
    }

    @Test
    fun `l itineraire refuse une base malformee avec un message actionnable`() = runBlocking {
        val from = RoutePoint(48.8566, 2.3522)
        val to = RoutePoint(48.8606, 2.3376)
        assertActionableNetwork(HttpRouteClient("::::").route(from, to))
        assertActionableNetwork(HttpRouteClient("::::").health())
    }

    @Test
    fun `le websocket refuse une URL malformee au lieu de planter`() {
        val transport = OkHttpWsTransport(OkHttpClient())
        assertFalse(transport.open("::::", onText = {}, onClosed = {}, onOpen = {}))
        assertFalse(transport.send("bonjour"))
    }
}

/**
 * Erreur réseau actionnable : le type historique est gardé, le message guide vers
 * Réglages au lieu du jargon du parseur (`Expected URL scheme…`).
 */
private fun assertActionableNetwork(result: Result<*>) {
    val error = result.exceptionOrNull()
    assertTrue("attendu Network, obtenu $error", error is ApiError.Network)
    assertTrue("message actionnable attendu, obtenu ${error?.message}", error?.message?.contains("Settings") == true)
}
