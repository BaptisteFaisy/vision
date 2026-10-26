package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/release` : extinction volontaire du GPU à la fermeture de l'app. */
class SamLifecycleTest {

    @Test
    fun `release envoie l en-tete de confirmation sur release`() = runBlocking {
        val server = TestHttpServer { request ->
            if (request.path == "/release" && request.headers["x-sam-release"] == "confirm") {
                TestHttpServer.Response(200, """{"released":true,"in_seconds":1}""")
            } else {
                TestHttpServer.Response(400, """{"detail":"en-tete manquant"}""")
            }
        }
        try {
            assertTrue(releaseSam(server.baseUrl).getOrThrow().released)
            val sent = server.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("/release", sent.path)
        } finally {
            server.close()
        }
    }

    @Test
    fun `un refus du serveur donne false sans lever`() = runBlocking {
        val server = TestHttpServer { TestHttpServer.Response(400, """{"detail":"refusé"}""") }
        try {
            assertFalse(releaseSam(server.baseUrl).getOrThrow().released)
        } finally {
            server.close()
        }
    }

    @Test
    fun `une base vide ne fait aucun appel`() = runBlocking {
        assertFalse(releaseSam("").getOrThrow().released)
    }

    @Test
    fun `le refus du serveur expose le code et le corps`() = runBlocking {
        val server = TestHttpServer { TestHttpServer.Response(400, """{"detail":"en-tête manquant"}""") }
        try {
            val outcome = releaseSam(server.baseUrl).getOrThrow()
            assertEquals(400, outcome.code)
            assertTrue(outcome.body.contains("en-tête"))
        } finally {
            server.close()
        }
    }
}
