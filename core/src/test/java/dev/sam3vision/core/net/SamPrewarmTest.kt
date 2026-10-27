package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamPrewarmTest {

    private var healthCode = 200
    private val server = TestHttpServer { request ->
        when (request.path) {
            "/health" -> TestHttpServer.Response(
                healthCode,
                """{"ok":true,"model":"sam3.1-fast","device":"cuda","gpu":"A100"}""",
            )
            else -> TestHttpServer.Response(404, "{}")
        }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `prewarm reussit sur health ok en un seul appel`() = runBlocking {
        assertTrue(prewarmSam(server.baseUrl))

        assertEquals(1, server.requests.size)
        assertEquals("/health", server.requests.single().path)
    }

    @Test
    fun `prewarm echoue sans bloquer sur health en erreur`() = runBlocking {
        healthCode = 500

        assertFalse(prewarmSam(server.baseUrl))

        assertEquals(1, server.requests.size)
    }
}
