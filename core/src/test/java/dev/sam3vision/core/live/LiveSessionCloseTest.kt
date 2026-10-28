package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Arrêt net d'une session : le transport est libéré (le conteneur Modal peut
 * retomber à zéro) et la frame en vol est révoquée (sa réponse tardive est jetée).
 */
class LiveSessionCloseTest {

    private class ClosingClient : LiveClient {
        var closed = 0

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> =
            Result.success(LiveFrame(640, 480, 321, emptyList()))

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(true, "sam3.1", "cuda", "A100"))

        override fun close() {
            closed += 1
        }
    }

    @Test
    fun `close libere le transport`() {
        val client = ClosingClient()
        val session = LiveSession(client)

        session.close()

        assertEquals(1, client.closed)
    }

    @Test
    fun `close revoque l_epoque`() {
        val session = LiveSession(ClosingClient())
        val era = session.generation

        session.close()

        assertFalse(session.isCurrent(era))
    }
}
