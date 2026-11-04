package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hystérésis anti-oscillation du repli HTTP, testée **sans réseau** : le repli colle
 * après [HTTP_STICK_AFTER] échec(s) WS de suite et ne se décolle qu'après
 * [HTTP_UNSTICK_AFTER] succès de suite — un succès isolé sur une re-sonde ne fait plus
 * clignoter la jambe HTTP↔WS quand le socket flappe. Un échec annule la confirmation
 * en cours, et réciproquement (au compteur de frames, déterministe, sans horloge).
 */
class WsHttpFallbackHysteresisTest {

    private val base = "https://sam.example"

    /** Transport flappant : une ouverture sur deux refuse, chaque envoi répond. */
    private class AlternatingOpenTransport : WsTransport {
        var opens = 0
        val sent = mutableListOf<String>()
        private var onText: ((String) -> Unit)? = null

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
            headers: Map<String, String>,
        ): Boolean {
            opens += 1
            if (opens % 2 == 1) return false
            this.onText = onText
            onOpen()
            return true
        }

        override fun send(text: String): Boolean {
            sent += text
            onText?.invoke(NetContract.frameReply(JSONObject(text).getInt("id")))
            return true
        }

        override fun sendBytes(bytes: ByteArray): Boolean = false

        override fun close() = Unit
    }

    @Test
    fun `T1 - par defaut colle au 1er echec, decolle au 2e succes`() {
        val policy = WsHttpFallback()

        policy.onWsFailed()
        assertTrue(policy.active)

        policy.onWsServed()
        assertTrue("un succès isolé ne décolle pas", policy.active)

        policy.onWsServed()
        assertFalse(policy.active)
    }

    @Test
    fun `T2 - un echec annule la confirmation en cours`() {
        val policy = WsHttpFallback()

        policy.onWsFailed()
        policy.onWsServed()
        assertEquals(1, policy.snapshot().wsServed)

        policy.onWsFailed()
        assertTrue(policy.active)
        assertEquals("confirmation annulée", 0, policy.snapshot().wsServed)

        policy.onWsServed()
        assertTrue("il faut 2 succès frais", policy.active)
        policy.onWsServed()
        assertFalse(policy.active)
    }

    @Test
    fun `T3 - unstickAfter 1, decollement immediat configure`() {
        val policy = WsHttpFallback(unstickAfter = 1)

        policy.onWsFailed()
        assertTrue(policy.active)
        policy.onWsServed()
        assertFalse(policy.active)
    }

    @Test
    fun `T4 - stickAfter 2, un echec isole ne colle pas`() {
        val policy = WsHttpFallback(stickAfter = 2)

        policy.onWsFailed()
        assertFalse("échec isolé : pas de repli", policy.active)

        policy.onWsServed()
        policy.onWsFailed()
        assertFalse("le succès a cassé la série", policy.active)

        policy.onWsFailed()
        assertTrue("2 échecs de suite : repli", policy.active)
    }

    @Test
    fun `T5 - onChange ne signale que les vraies bascules`() {
        val events = mutableListOf<Boolean>()
        val policy = WsHttpFallback(onChange = events::add)

        policy.onWsFailed()
        policy.onWsFailed()
        policy.onWsServed()
        assertEquals(listOf(true), events)

        policy.onWsServed()
        policy.onWsServed()
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `T6 - compteurs et snapshot, reset remet tout a zero`() {
        val policy = WsHttpFallback(probeEvery = 2)

        policy.onWsFailed()
        assertTrue("la déclencheuse comptait : sonde aussitôt", policy.routeWs())
        policy.onWsServed()
        policy.onWsServed()

        val snap = policy.snapshot()
        assertFalse(snap.active)
        assertEquals(0, snap.httpServed)
        assertEquals(0, snap.wsServed)
        assertEquals(0, snap.wsFailed)
        assertEquals(1, snap.sticks)
        assertEquals(1, snap.unsticks)
        assertEquals(1, snap.probes)
        assertEquals(1, policy.stickCount)
        assertEquals(1, policy.unstickCount)
        assertEquals(1, policy.probeCount)

        policy.onWsFailed()
        policy.onWsServed()
        policy.reset()
        assertEquals(HttpFallbackSnapshot(false, 0, 0, 0, 0, 0, 0), policy.snapshot())
        assertEquals(0, policy.stickCount)
        assertEquals(0, policy.unstickCount)
        assertEquals(0, policy.probeCount)
        assertTrue(policy.routeWs())
    }

    @Test
    fun `T7 - le client ne clignote pas sur un WS flappant`() = runBlocking {
        val transport = AlternatingOpenTransport()
        val http = RecordingLiveClient()
        val fallback = WsHttpFallback(probeEvery = 1)
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false, httpFallback = fallback)

        repeat(6) { i ->
            client.live("QQ==", null, 640, 4).getOrThrow()
            assertTrue("frame ${i + 1} : repli stable", client.httpFallbackActive)
            assertEquals("http", client.leg)
        }

        assertEquals("3 échecs : 3 POST", 3, http.liveCalls)
        assertEquals("3 succès : 3 envois WS", 3, transport.sent.size)
        assertEquals(1, fallback.stickCount)
        assertEquals("jamais décollé", 0, fallback.unstickCount)
        client.close()
    }
}
