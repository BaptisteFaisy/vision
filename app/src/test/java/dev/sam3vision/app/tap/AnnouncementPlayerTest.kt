package dev.sam3vision.app.tap

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.net.TtsClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pré-chauffage des clips du mode tap : les mots manquants sont synthétisés en
 * parallèle (3×RTT → 1×RTT), et le bilan suit l'ordre d'entrée comme en série.
 */
class AnnouncementPlayerTest {

    /** Faux TTS : 150 ms par mot, chevauchement maximal observé. */
    private class FakeTts : TtsClient {
        val texts = mutableListOf<String>()
        private val inFlight = AtomicInteger(0)
        var maxInFlight = 0
            private set

        override suspend fun synthesize(text: String): Result<ByteArray> {
            synchronized(texts) { texts += text }
            val now = inFlight.incrementAndGet()
            synchronized(this) { maxInFlight = maxOf(maxInFlight, now) }
            try {
                delay(150)
            } finally {
                inFlight.decrementAndGet()
            }
            return Result.success("mp3".toByteArray())
        }
    }

    private fun player(dir: File, tts: TtsClient, scope: kotlinx.coroutines.CoroutineScope) =
        AnnouncementPlayer(dir, tts, SpeechPlayer(dir), scope)

    @Test
    fun `prewarm synthetise les mots manquants en parallele`() = runBlocking {
        val dir = Files.createTempDirectory("annonces").toFile()
        val tts = FakeTts()

        val report = player(dir, tts, this).prewarm()

        assertEquals(listOf("Launched", "Sent", "Failed").sorted(), tts.texts.sorted())
        assertEquals(0, report.ready)
        assertEquals(3, report.generated)
        assertTrue(report.missing.isEmpty())
        assertTrue("attendu un chevauchement, observé ${tts.maxInFlight}", tts.maxInFlight >= 2)
    }

    @Test
    fun `prewarm ne regenere pas les clips frais`() = runBlocking {
        val dir = Files.createTempDirectory("annonces-chaud").toFile()
        val tts = FakeTts()
        val announcements = player(dir, tts, this)

        announcements.prewarm()
        val second = announcements.prewarm()

        assertEquals(3, second.ready)
        assertEquals(0, second.generated)
        assertEquals(3, tts.texts.size)
    }
}
