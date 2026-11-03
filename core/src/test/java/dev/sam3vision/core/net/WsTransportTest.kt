package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport WebSocket réel contre faux serveur : un handshake refusé (HTTP ≠ 101)
 * préserve le code pour le diagnostic, au lieu d'une erreur générique sans motif.
 */
class WsTransportTest {

    @Test
    fun `handshake refuse 404 preserve le code`() {
        TestHttpServer { TestHttpServer.Response(404, "{}") }.use { server ->
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            val transport = OkHttpWsTransport(OkHttpClient())

            assertTrue(transport.open("${server.baseUrl}/live/ws", onText = {}, onClosed = { failure = it; done.countDown() }, onOpen = {}))

            assertTrue("handshake refusé attendu", done.await(10, TimeUnit.SECONDS))
            val refused = failure as WsHandshakeRefused
            assertEquals(404, refused.code)
        }
    }
}
