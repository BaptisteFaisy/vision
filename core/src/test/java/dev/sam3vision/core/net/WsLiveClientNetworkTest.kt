package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tranche RÉSEAU (FIX-16/18, FIX-4/8, FIX-17) : budgets, reprise immédiate, cancel
 * one-way, ping applicatif, timeout décroissant et jambe sans sonde — sans réseau
 * ([ContractWsTransport]) ni GPU.
 */
class WsLiveClientNetworkTest {

    private val base = "https://sam.example"

    @Test
    fun `les budgets steady valent 5 s, froid et reveil inchanges`() {
        assertEquals(5L, LIVE_TIMEOUT_SECONDS)
        assertEquals(180L, WAKE_TIMEOUT_SECONDS)
    }

    @Test
    fun `useTimeoutMs fait retomber le budget sans ouvrir de socket`() = runBlocking {
        val transport = ContractWsTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 30_000, http = RecordingLiveClient(), binaryWs = false)

        client.useTimeoutMs(80)

        assertEquals(80, client.currentTimeoutMs)
        assertNull("aucun socket ouvert", transport.openedUrl)
        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()
        assertTrue("budget de 80 ms honoré, obtenu $failure", failure is ApiError.Timeout)
        client.close()
    }
}
