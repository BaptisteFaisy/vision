package dev.sam3vision.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un 404 de Modal veut dire deux choses très différentes : app **non déployée**
 * (« invalid function call ») ou ressource absente (session de tracker inconnue).
 * Le premier cas ne doit pas être retenté, le second si (le client rouvre la session).
 */
class HttpErrorTest {

    @Test
    fun `404 modal app arretee donne BackendStopped`() {
        val error = httpError(404, "modal-http: invalid function call\n")
        assertTrue("attendu BackendStopped, obtenu $error", error is ApiError.BackendStopped)
        assertTrue(error.message!!.contains("SAM GPU off"))
    }

    @Test
    fun `404 de session inconnue reste une erreur HTTP`() {
        val error = httpError(404, """{"detail":"unknown session abc"}""")
        assertTrue("attendu Http, obtenu $error", error is ApiError.Http)
        assertEquals(404, (error as ApiError.Http).code)
    }

    @Test
    fun `les codes courants gardent leur message`() {
        assertTrue(httpError(401, "nope") is ApiError.InvalidKey)
        assertTrue(httpError(429, "") is ApiError.RateLimited)
        assertTrue(httpError(500, "boom") is ApiError.Http)
    }

    /** 409 du proxy LLM : refus pour une limite côté service, message actionnable. */
    @Test
    fun `409 parle de limite IA et de nouvelle tentative`() {
        val error = httpError(409, """{"detail":"limite atteinte"}""")
        assertTrue("attendu RateLimited, obtenu $error", error is ApiError.RateLimited)
        assertTrue(error.message!!.contains("409"))
        assertTrue(error.message!!.contains("try again"))
    }
}