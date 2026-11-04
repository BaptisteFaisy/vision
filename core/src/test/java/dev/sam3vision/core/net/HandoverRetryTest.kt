package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveHealth
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un seul nouvel essai quand le DNS meurt pendant la bascule lunettes →
 * cellulaire ([isHandoverTransient]) : le Wi-Fi des lunettes devient la route
 * par défaut avant que le cellulaire imposé ne soit prêt, et la première
 * résolution suit cette route morte. Le DNS n'a rien envoyé : le 2e essai
 * est gratuit (zéro inférence rejouée) et part sur la route installée.
 * Hors session lunettes, politique inchangée : aucun retry supplémentaire.
 */
class HandoverRetryTest {

    /** Client qui tue sa première requête en DNS, puis laisse passer. */
    private data class FlakyClient(val client: OkHttpClient, val calls: AtomicInteger)

    private fun flakyOnceClient(base: OkHttpClient): FlakyClient {
        val calls = AtomicInteger(0)
        val first = AtomicBoolean(true)
        val client = base.newBuilder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                if (first.getAndSet(false)) throw UnknownHostException("dns mort (bascule lunettes)")
                chain.proceed(chain.request())
            }
            .build()
        return FlakyClient(client, calls)
    }

    private fun networkWith(cause: Throwable?): ApiError.Network = ApiError.Network("x", cause = cause)

    @After
    fun clearRoute() {
        BackendRoute.clear()
    }

    @Test
    fun `handover transitoire = UnknownHost sous route active`() {
        BackendRoute.install(SocketFactory.getDefault())

        assertTrue(isHandoverTransient(networkWith(UnknownHostException("dns mort"))))
    }

    @Test
    fun `hors route rien n'est handover-transitoire`() {
        assertFalse(isHandoverTransient(networkWith(UnknownHostException("dns mort"))))
        assertFalse(isHandoverTransient(ApiError.Timeout()))
    }

    @Test
    fun `sous route seules les pannes DNS comptent`() {
        BackendRoute.install(SocketFactory.getDefault())

        assertFalse(isHandoverTransient(networkWith(null)))
        assertFalse(isHandoverTransient(networkWith(SSLException("tls"))))
        assertFalse(isHandoverTransient(networkWith(IOException("reset"))))
        assertFalse(isHandoverTransient(ApiError.Timeout()))
        assertFalse(isHandoverTransient(ApiError.BackendStopped()))
        assertFalse(isHandoverTransient(ApiError.Http(500, "x")))
    }

    @Test
    fun `segment rejoue une fois le DNS mort en bascule puis reussit`() = runBlocking {
        NetContract.server(body = """{"width":1,"height":1,"objects":[]}""").use { server ->
            val flaky = flakyOnceClient(defaultHttpClient())
            BackendRoute.install(SocketFactory.getDefault())

            val result = HttpSamClient(server.baseUrl, flaky.client, routeRetryDelayMs = 0)
                .segment("QUJD", null)

            assertTrue(result.isSuccess)
            assertEquals(2, flaky.calls.get())
            // Le 1er essai est mort en DNS : une seule requête est partie, pas de 2e inférence.
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `segment ne rejoue pas le DNS mort hors bascule`() = runBlocking {
        NetContract.server(body = """{"width":1,"height":1,"objects":[]}""").use { server ->
            val flaky = flakyOnceClient(defaultHttpClient())

            val failure = HttpSamClient(server.baseUrl, flaky.client, routeRetryDelayMs = 0)
                .segment("QUJD", null).exceptionOrNull()

            assertTrue(failure is ApiError.Network)
            assertTrue((failure as ApiError.Network).cause is UnknownHostException)
            assertEquals(1, flaky.calls.get())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `segment ne rejoue pas un 400 en bascule`() = runBlocking {
        NetContract.server(code = 400, body = """{"detail":"bad"}""").use { server ->
            BackendRoute.install(SocketFactory.getDefault())

            val failure = HttpSamClient(server.baseUrl, routeRetryDelayMs = 0)
                .segment("QUJD", null).exceptionOrNull()

            assertTrue(failure is ApiError.Http)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `health rejoue le DNS mort en bascule puis reussit`() = runBlocking {
        NetContract.server(body = """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100"}""").use { server ->
            val flaky = flakyOnceClient(liveHttpClient())
            BackendRoute.install(SocketFactory.getDefault())

            val result = HttpLiveClient(server.baseUrl, wakeClient = flaky.client, retryDelayMs = 0).health()

            assertEquals(LiveHealth(true, "sam3.1", "cuda", "A100"), result.getOrThrow())
            assertEquals(2, flaky.calls.get())
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `live rejoue une fois le DNS mort en bascule puis reussit`() = runBlocking {
        NetContract.server(body = NetContract.frameReply()).use { server ->
            val flaky = flakyOnceClient(liveHttpClient())
            BackendRoute.install(SocketFactory.getDefault())

            val result = HttpLiveClient(server.baseUrl, client = flaky.client, routeRetryDelayMs = 0)
                .live("SUVLFw==", null, 512, 6)

            assertTrue(result.isSuccess)
            assertEquals(2, flaky.calls.get())
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `live ne rejoue pas le DNS mort hors bascule`() = runBlocking {
        NetContract.server(body = NetContract.frameReply()).use { server ->
            val flaky = flakyOnceClient(liveHttpClient())

            val failure = HttpLiveClient(server.baseUrl, client = flaky.client, routeRetryDelayMs = 0)
                .live("SUVLFw==", null, 512, 6).exceptionOrNull()

            assertTrue(failure is ApiError.Network)
            assertEquals(1, flaky.calls.get())
            assertTrue(server.requests.isEmpty())
        }
    }
}
