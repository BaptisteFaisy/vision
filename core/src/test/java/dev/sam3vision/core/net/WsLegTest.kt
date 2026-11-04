package dev.sam3vision.core.net

import dev.sam3vision.core.live.LiveSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Jambe du direct, lue par la sonde à chaque publication : binaire M1, JSON texte
 * (repli) ou POST (repli HTTP) — les diags disent enfin par où passent les frames.
 */
class WsLegTest {

    private val base = "https://sam.example"

    private fun client(
        binaryWs: Boolean = false,
        httpFallback: WsHttpFallback = WsHttpFallback(),
    ) = WsLiveClient(
        base,
        ContractWsTransport(),
        1_000,
        RecordingLiveClient(),
        binaryWs = binaryWs,
        httpFallback = httpFallback,
    )

    @Test
    fun `legOf - le repli http prime, sinon binaire ou texte`() {
        assertEquals("http", legOf(httpFallback = true, binary = true))
        assertEquals("http", legOf(httpFallback = true, binary = false))
        assertEquals("binary", legOf(httpFallback = false, binary = true))
        assertEquals("text", legOf(httpFallback = false, binary = false))
    }

    @Test
    fun `le client part en texte et suit useBinaryWs`() {
        val ws = client(binaryWs = true)

        assertEquals("binary", ws.leg)
        ws.useBinaryWs(false)
        assertEquals("text", ws.leg)
        ws.useBinaryWs(true)
        assertEquals("binary", ws.leg)
    }

    @Test
    fun `le repli http colle la jambe http`() {
        val fallback = WsHttpFallback()
        val ws = client(binaryWs = true, httpFallback = fallback)

        assertEquals("binary", ws.leg)
        fallback.onWsFailed()
        assertEquals("http", ws.leg)
        fallback.onWsServed()
        assertEquals("le décollement rend le binaire", "binary", ws.leg)
    }

    @Test
    fun `la session lit la jambe du client, http sans socket`() {
        assertEquals("texte par défaut", "text", LiveSession(client(), clock = { 0L }).leg)
        assertEquals("binary", LiveSession(client(binaryWs = true), clock = { 0L }).leg)
        assertEquals("http", LiveSession(RecordingLiveClient(), clock = { 0L }).leg)
    }
}
