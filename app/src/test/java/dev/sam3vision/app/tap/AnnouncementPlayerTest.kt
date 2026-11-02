package dev.sam3vision.app.tap

import dev.sam3vision.app.SpeechPlayer
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pré-chauffage des clips du mode tap : les mots manquants sont copiés depuis
 * les MP3 embarqués (zéro réseau), et le bilan suit l'ordre d'entrée.
 */
class AnnouncementPlayerTest {

    /** Embarqué factice : écrit un faux mp3, échecs programmables. */
    private class FakeBundled(private val failOn: Set<Announcement> = emptySet()) : TapBundled {
        val filled = mutableListOf<Announcement>()

        override fun fill(word: Announcement, file: File): Boolean {
            filled += word
            if (word in failOn) return false
            file.parentFile?.mkdirs()
            file.writeBytes("mp3".toByteArray())
            return true
        }
    }

    private fun player(dir: File, bundled: TapBundled) =
        AnnouncementPlayer(dir, bundled, SpeechPlayer(dir))

    @Test
    fun `prewarm copie les mots manquants depuis l embarque`() = runBlocking {
        val dir = Files.createTempDirectory("annonces").toFile()
        val bundled = FakeBundled()

        val report = player(dir, bundled).prewarm()

        assertEquals(
            listOf(Announcement.LAUNCHED, Announcement.SENT, Announcement.FAILED).sorted(),
            bundled.filled.sorted(),
        )
        assertEquals(0, report.ready)
        assertEquals(3, report.generated)
        assertTrue(report.missing.isEmpty())
    }

    @Test
    fun `prewarm ne recopie pas les clips frais`() = runBlocking {
        val dir = Files.createTempDirectory("annonces-chaud").toFile()
        val bundled = FakeBundled()
        val announcements = player(dir, bundled)

        announcements.prewarm()
        val second = announcements.prewarm()

        assertEquals(3, second.ready)
        assertEquals(0, second.generated)
        assertEquals(3, bundled.filled.size)
    }

    @Test
    fun `prewarm signale les mots sans clip`() = runBlocking {
        val dir = Files.createTempDirectory("annonces-manquant").toFile()
        val bundled = FakeBundled(failOn = setOf(Announcement.SENT))

        val report = player(dir, bundled).prewarm()

        assertEquals(0, report.ready)
        assertEquals(2, report.generated)
        assertEquals(listOf("Sent"), report.missing)
    }

    @Test
    fun `speak sans clip renvoie faux sans lecture`() = runBlocking {
        val dir = Files.createTempDirectory("annonces-muet").toFile()
        val bundled = FakeBundled(failOn = setOf(Announcement.FAILED))

        assertFalse(player(dir, bundled).speak(Announcement.FAILED))
    }
}
