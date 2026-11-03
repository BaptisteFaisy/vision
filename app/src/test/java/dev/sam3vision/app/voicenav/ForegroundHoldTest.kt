package dev.sam3vision.app.voicenav

import dev.sam3vision.app.safety.CRASH_LOG_NAME
import dev.sam3vision.app.safety.CrashGuard
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Compte-rendu de la tenue du premier plan ([reportForegroundHold]) : un refus du système
 * laisse une ligne au journal persistant (`type=event`), pas seulement en logcat volatile.
 * Sans ça, un nouveau `ForegroundServiceDidNotStartInTimeException` serait aussi invisible
 * que celui du 01/11 (retrouvé uniquement dans le dropbox système).
 */
class ForegroundHoldTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun ouvrirJournal() {
        dir = Files.createTempDirectory("foreground-hold").toFile()
        file = File(dir, CRASH_LOG_NAME)
        CrashGuard.attach(file)
    }

    @After
    fun fermerJournal() {
        CrashGuard.flush()
        CrashGuard.attach(File(dir, "detache-${System.nanoTime()}.log"))
        dir.deleteRecursively()
    }

    @Test
    fun `refus avec incidents — chaque incident est journalise en type event`() {
        val before = CrashGuard.incidents()
        reportForegroundHold(
            tag = "Essai",
            origin = "essai-premier-plan",
            held = false,
            label = "premier plan refusé, sortie propre",
            incidents = listOf("premier plan: IllegalStateException", "sortie après refus: Erreur"),
        )
        assertTrue(CrashGuard.flush())
        assertEquals(before + 2, CrashGuard.incidents())

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue("origine : ${lines[0]}", lines[0].contains("origin=essai-premier-plan"))
        assertTrue("type : ${lines[0]}", lines[0].contains("type=event"))
        assertTrue("détail : ${lines[0]}", lines[0].contains("premier plan refusé, sortie propre"))
        assertTrue("incident : ${lines[1]}", lines[1].contains("sortie après refus: Erreur"))
    }

    @Test
    fun `refus muet — le refus reste trace au journal`() {
        reportForegroundHold(
            tag = "Essai",
            origin = "essai-premier-plan",
            held = false,
            label = "premier plan refusé, sortie propre",
            incidents = emptyList(),
        )
        assertTrue(CrashGuard.flush())

        val lines = file.readLines()
        assertEquals(1, lines.size)
        assertTrue("type : ${lines[0]}", lines[0].contains("type=event"))
        assertTrue("refus : ${lines[0]}", lines[0].contains("msg=[premier plan refusé, sortie propre]"))
    }

    @Test
    fun `tenue propre — rien n'est journalise`() {
        val before = CrashGuard.incidents()
        reportForegroundHold(
            tag = "Essai",
            origin = "essai-premier-plan",
            held = true,
            label = "premier plan",
            incidents = emptyList(),
        )
        assertTrue(CrashGuard.flush())
        assertEquals(before, CrashGuard.incidents())
    }
}
