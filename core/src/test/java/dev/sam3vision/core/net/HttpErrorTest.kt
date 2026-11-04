package dev.sam3vision.core.net

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun `un corps d erreur geant est borne a 8 Ko`() {
        val error = httpError(500, "x".repeat(100_000))

        assertTrue("attendu Http, obtenu $error", error is ApiError.Http)
        assertEquals(MAX_ERROR_BODY_CHARS, (error as ApiError.Http).body.length)
    }

    @Test
    fun `un petit corps d erreur est garde tel quel`() {
        val error = httpError(500, "boom")

        assertTrue("attendu Http, obtenu $error", error is ApiError.Http)
        assertEquals("boom", (error as ApiError.Http).body)
    }

    @Test
    fun `un echec TLS parle de TLS, pas de reseau generique`() {
        val cause = SSLHandshakeException("certificate verify failed")
        val error = cause.toApiError()

        assertTrue("attendu Network, obtenu $error", error is ApiError.Network)
        assertEquals(TLS_FAILED, error.message)
        assertSame(cause, error.cause)
        assertFalse("un TLS cassé ne guérit pas en réessayant seul", error.isTransient)
    }

    @Test
    fun `un hote inconnu renvoie vers les reglages`() {
        val error = UnknownHostException("gaiamelie--sam3-eu-a100-sam3live-web.eu-west.modal.runx").toApiError()

        assertTrue("attendu Network, obtenu $error", error is ApiError.Network)
        assertTrue(error.message!!.contains("Settings"))
        assertFalse(error.isTransient)
    }

    @Test
    fun `le 404 modal reste detecte sur un corps geant`() {
        val error = httpError(404, "modal-http: invalid function call\n" + "x".repeat(100_000))

        assertTrue("attendu BackendStopped, obtenu $error", error is ApiError.BackendStopped)
    }

    /**
     * Incident Samsung du 04/11/2026 : le log ne disait que « Timeout backend
     * unreachable: timeout », sans dire lecture ou connexion ni avec quel détail —
     * la cause transport était avalée. Elle est désormais gardée (le journal
     * l'affiche en « + Cause message ») et le timeout reste transient.
     */
    @Test
    fun `un timeout socket garde sa cause transport`() {
        val cause = SocketTimeoutException("timeout")
        val error = cause.toApiError()

        assertTrue("attendu Timeout, obtenu $error", error is ApiError.Timeout)
        assertSame(cause, error.cause)
        assertTrue("un timeout reste retentable", error.isTransient)
    }

    @Test
    fun `un timeout interrompu garde sa cause transport`() {
        val cause = InterruptedIOException("interrupted")
        val error = cause.toApiError()

        assertTrue("attendu Timeout, obtenu $error", error is ApiError.Timeout)
        assertSame(cause, error.cause)
        assertTrue("un timeout reste retentable", error.isTransient)
    }
}
