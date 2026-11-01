package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LiveSession.runFrameBytes` (O6) : le JPEG brut traverse la séance (créneau,
 * ère, statistiques, régulateur) jusqu'à [LiveClient.liveBytes], sans base64.
 */
class LiveSessionBytesTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03)

    /** Client binaire pur : `live` est interdit, seuls les octets bruts passent. */
    private class BytesOnlyClient(
        private val frame: LiveFrame = LiveFrame(width = 512, height = 1138, elapsedMs = 118, objects = emptyList()),
    ) : LiveClient {
        var received: ByteArray? = null
            private set

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> = error("le chemin octets ne doit jamais encoder en base64")

        override suspend fun liveBytes(
            jpeg: ByteArray,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            received = jpeg
            return Result.success(frame)
        }

        override suspend fun health(): Result<LiveHealth> = error("hors sujet")
    }

    @Test
    fun `runFrameBytes envoie les octets bruts et consigne les stats`() = runBlocking {
        val client = BytesOnlyClient()
        val session = LiveSession(client)

        val result = session.runFrameBytes(jpeg)?.getOrThrow()

        assertEquals(512, result?.width)
        assertTrue(
            "octets intacts jusqu'au client",
            client.received?.contentEquals(jpeg) ?: false,
        )
        assertEquals(1, session.stats.samples)
        assertEquals(1, session.serverStats.samples)
    }
}
