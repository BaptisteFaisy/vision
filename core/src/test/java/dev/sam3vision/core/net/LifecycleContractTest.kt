package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de transport de l'extinction (`POST /release`) : en-tête de confirmation, corps
 * JSON vide, User-Agent, **une seule requête par appel** et refus d'autorisation/crédits/quota
 * remontés en erreur typée (un `released=false` muet laissait la carte allumée sans explication).
 *
 * [SamLifecycleTest] verrouille déjà le cas nominal, le refus 400 et la base vide ; ici :
 * forme exacte de la requête, comptage des tentatives, corps illisible et timeout.
 */
class LifecycleContractTest {

    @Test
    fun `release est un POST JSON confirme et identifie`() = runBlocking {
        NetContract.server(body = """{"released":true,"in_seconds":1}""").use { server ->
            assertTrue(releaseSam(server.baseUrl).getOrThrow().released)

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/release", request.path)
            assertEquals("confirm", request.headers[RELEASE_HEADER])
            assertEquals(USER_AGENT, request.headers["user-agent"])
            assertEquals("application/json; charset=utf-8", request.headers["content-type"])
            assertEquals("{}", request.body)
        }
    }

    @Test
    fun `un 500 rend false avec son code, en une seule requete`() = runBlocking {
        NetContract.server(code = 500, body = """{"detail":"demarrage"}""").use { server ->
            val outcome = releaseSam(server.baseUrl).getOrThrow()

            assertFalse(outcome.released)
            assertEquals(500, outcome.code)
            assertEquals("un refus n'est jamais rejoué", 1, server.requests.size)
        }
    }

    /** Le rejeu doublait l'extinction : un refus ne doit partir qu'une fois. */
    @Test
    fun `un refus n est jamais rejoue meme si la suite repondrait oui`() = runBlocking {
        val calls = AtomicInteger()
        TestHttpServer { _ ->
            if (calls.incrementAndGet() == 1) {
                TestHttpServer.Response(404, """{"detail":"absent"}""")
            } else {
                TestHttpServer.Response(200, """{"released":true,"in_seconds":0}""")
            }
        }.use { server ->
            assertFalse(releaseSam(server.baseUrl).getOrThrow().released)
            assertEquals("une seule requête, jamais deux", 1, server.requests.size)
        }
    }

    @Test
    fun `un 200 sans released rend false`() = runBlocking {
        NetContract.server(body = """{"ok":true}""").use { server ->
            assertFalse(releaseSam(server.baseUrl).getOrThrow().released)
        }
    }

    @Test
    fun `un corps non JSON rend false sans lever`() = runBlocking {
        NetContract.server(body = "<html>Bad Gateway</html>", contentType = "text/html").use { server ->
            val outcome = releaseSam(server.baseUrl).getOrThrow()
            assertFalse(outcome.released)
            assertTrue("le corps reste disponible pour le diagnostic", outcome.body.contains("Bad Gateway"))
        }
    }

    /**
     * Un 401/402/403/429 n'est pas un verdict mais un refus : l'écran de fermeture doit
     * pouvoir dire *pourquoi* la carte reste allumée (clé, crédits, quota).
     */
    @Test
    fun `un 401 devient InvalidKey en une seule requete`() = runBlocking {
        NetContract.server(code = 401, body = """{"detail":"clé invalide"}""").use { server ->
            val result = releaseSam(server.baseUrl)

            NetContract.assertError(result, ApiError.InvalidKey::class.java, "release 401")
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `un 402 devient PaymentRequired`() = runBlocking {
        NetContract.server(code = 402, body = """{"detail":"sans crédits"}""").use { server ->
            NetContract.assertError(
                releaseSam(server.baseUrl),
                ApiError.PaymentRequired::class.java,
                "release 402",
            )
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `un 429 devient RateLimited`() = runBlocking {
        NetContract.server(code = 429, body = """{"detail":"trop de requêtes"}""").use { server ->
            NetContract.assertError(
                releaseSam(server.baseUrl),
                ApiError.RateLimited::class.java,
                "release 429",
            )
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `un delai depasse rend Timeout sans seconde tentative`() = runBlocking {
        NetContract.slowServer().use { server ->
            val result = releaseSam(server.baseUrl, NetContract.impatientClient())

            NetContract.assertError(result, ApiError.Timeout::class.java, "release lent")
            assertEquals("pas de rejeu après un délai dépassé", 1, server.requests.size)
        }
    }

    @Test
    fun `une base vide ne joint personne`() = runBlocking {
        val outcome = releaseSam("").getOrThrow()
        assertFalse(outcome.released)
        assertEquals(0, outcome.code)
    }
}