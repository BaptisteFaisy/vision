package dev.sam3vision.app.safety

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Filet d'exception ([CrashGuard]) : là où il n'y avait avant **aucun** handler en
 * production (zéro `CoroutineExceptionHandler`, zéro `setDefaultUncaughtExceptionHandler`),
 * une faute de coroutine doit laisser une trace et laisser l'app vivre ; une faute de fil
 * doit être tracée **puis** rendue au système (on ne ressuscite pas un process dont on ne
 * connaît pas l'état). Le journal, lui, est borné et ne recopie jamais une clé.
 */
class CrashGuardTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun ouvrirJournal() {
        dir = Files.createTempDirectory("crash-guard").toFile()
        file = File(dir, CRASH_LOG_NAME)
        CrashGuard.attach(file)
    }

    @After
    fun fermerJournal() {
        CrashGuard.flush()
        // Journal détaché : plus aucune écriture ne vise le dossier supprimé.
        CrashGuard.attach(File(dir, "detache-${System.nanoTime()}.log"))
        dir.deleteRecursively()
    }

    @Test
    fun `une coroutine fautive est journalisee et ne tue rien`() = runTest {
        val scope: CoroutineScope = CrashGuard.scope("essai", Dispatchers.Unconfined)
        val before = CrashGuard.incidents()

        // Avant le filet, cette ligne tuait le process (handler non capturé).
        scope.launch { throw IllegalStateException("boum probe frame") }
        assertTrue(CrashGuard.flush())
        assertEquals(before + 1, CrashGuard.incidents())

        val line = file.readLines().last()
        assertTrue("origine : $line", line.contains("origin=coroutine:essai"))
        assertTrue("type : $line", line.contains("type=java.lang.IllegalStateException"))
        assertTrue("message : $line", line.contains("msg=[boum probe frame]"))
        assertTrue("frames : $line", line.contains("frames=["))
        assertTrue("fil : $line", line.contains("thread="))
        assertFalse("une seule ligne physique", line.contains("\n"))
    }

    @Test
    fun `un scope filete reste vivant apres une faute`() = runTest {
        val scope = CrashGuard.scope("essai", Dispatchers.Unconfined)
        var apres = 0
        scope.launch { throw IllegalStateException("premiere") }
        scope.launch { apres += 1 }
        assertTrue(CrashGuard.flush())
        assertEquals("le superviseur survit, les freres tournent", 1, apres)
    }

    @Test
    fun `le handler de fil journalise puis delegue`() {
        var delegate: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, error -> delegate = error }
        val boom = IllegalStateException("fatal-thread")

        CrashGuard.uncaughtHandler(previous).uncaughtException(Thread("worker-test"), boom)

        assertSame("le système décide après la trace", boom, delegate)
        assertTrue(CrashGuard.flush())
        assertTrue(file.readText().contains("origin=thread:worker-test"))
    }

    @Test
    fun `le journal ne recopie jamais un secret`() {
        CrashGuard.note(
            "reseau",
            IllegalStateException(
                "POST https://api.test/v1?api_key=SECRETVALUE1 failed sk-abcdef123456 " +
                    "Bearer tok987654321 token: AUTRESECRET",
            ),
        )
        assertTrue(CrashGuard.flush())
        val text = file.readText()
        assertFalse("clé d'URL : $text", text.contains("SECRETVALUE1"))
        assertFalse("clé sk- : $text", text.contains("sk-abcdef123456"))
        assertFalse("jeton Bearer : $text", text.contains("tok987654321"))
        assertFalse("champ token : $text", text.contains("AUTRESECRET"))
        assertTrue("masque présent : $text", text.contains("***"))
    }

    @Test
    fun `le journal est borne — rotation au plafond, deux fichiers`() {
        val gros = IllegalStateException("x".repeat(180))
        repeat(40) {
            repeat(20) { CrashGuard.note("boucle", gros) }
            assertTrue("file drainée", CrashGuard.flush())
        }
        assertTrue(file.length() < JOURNAL_MAX_BYTES + 1_024L)
        assertTrue("journal tourné", File(file.path + ".1").isFile)
        val fichiers = dir.listFiles().orEmpty().count { it.name.startsWith(CRASH_LOG_NAME) }
        assertEquals("deux fichiers au plus", 2, fichiers)
    }

    @Test
    fun `un journal inaccessible ne fait jamais lever`() {
        CrashGuard.attach(dir) // un dossier : toute écriture échoue, silencieusement
        val before = CrashGuard.incidents()
        CrashGuard.note("disque", IllegalStateException("ko"))
        assertTrue(CrashGuard.flush())
        assertEquals(before + 1, CrashGuard.incidents())
    }

    @Test
    fun `redactSecrets aplatit et borne le message`() {
        assertEquals("", redactSecrets(null))
        assertEquals("ligne une ligne deux", redactSecrets("ligne une\nligne deux"))
        assertEquals("***", redactSecrets("sk-abcdef123456"))
        assertEquals(201, redactSecrets("y".repeat(400)).length)
        assertTrue(redactSecrets("y".repeat(400)).endsWith("…"))
    }
}
