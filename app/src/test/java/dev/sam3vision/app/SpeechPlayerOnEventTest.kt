package dev.sam3vision.app

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trace voix du lecteur : `play` à la demande, `stop` à la coupe, `done` en fin
 * normale. `cut` (coupe d'une lecture en vol) n'est pas observable en JVM :
 * `MediaPlayer` indisponible, toute lecture y échoue en synchrone.
 */
class SpeechPlayerOnEventTest {

    private fun tempDir(): File = Files.createTempDirectory("parole-1b").toFile()

    @Test
    fun `play demande puis echoue en JVM - stop, play, pas de done`() = runTest {
        val events = mutableListOf<String>()
        val player = SpeechPlayer(tempDir(), onEvent = { events += it })

        val result = player.play("faux-mp3".toByteArray())

        assertTrue(result.isFailure)
        assertEquals(listOf("stop", "play"), events)
    }

    @Test
    fun `playFile demande puis echoue en JVM - stop, play`() = runTest {
        val events = mutableListOf<String>()
        val player = SpeechPlayer(tempDir(), onEvent = { events += it })

        val result = player.playFile(File(tempDir(), "x.mp3"))

        assertTrue(result.isFailure)
        assertEquals(listOf("stop", "play"), events)
    }

    @Test
    fun `stop a vide - stop seul, pas de cut`() {
        val events = mutableListOf<String>()
        SpeechPlayer(tempDir(), onEvent = { events += it }).stop()

        assertEquals(listOf("stop"), events)
    }

    @Test
    fun `fin normale gardee - done une fois, rappel une fois`() {
        val events = mutableListOf<String>()
        val player = SpeechPlayer(tempDir(), onEvent = { events += it })
        var calls = 0

        val gen = player.armPlaybackForTest()
        player.finishDone(gen) { calls++ }
        player.finishDone(gen) { calls++ }

        assertEquals(listOf("done"), events)
        assertEquals(1, calls)
    }

    @Test
    fun `generation perimee - ni done ni rappel`() {
        val events = mutableListOf<String>()
        val player = SpeechPlayer(tempDir(), onEvent = { events += it })
        var calls = 0

        val stale = player.armPlaybackForTest()
        player.stop()
        events.clear()
        player.finishDone(stale) { calls++ }

        assertTrue(events.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `play en JVM - echec synchrone journalise PLAYER error avec fichier`() = runTest {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val player = SpeechPlayer(tempDir(), onPlaybackError = { errors += it }, onLog = { logs += it })

        val result = player.play("faux-mp3".toByteArray())

        assertTrue(result.isFailure)
        assertEquals(1, errors.size)
        assertEquals(1, logs.size)
        assertTrue(logs[0].startsWith("PLAYER error "))
        assertTrue(logs[0].contains("voice.mp3"))
    }

    @Test
    fun `playFile en JVM - journalise le nom du fichier`() = runTest {
        val logs = mutableListOf<String>()
        val dir = tempDir()
        val player = SpeechPlayer(dir, onLog = { logs += it })

        val result = player.playFile(File(dir, "clip.mp3"))

        assertTrue(result.isFailure)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("clip.mp3"))
    }

    @Test
    fun `playQueued en JVM - echec synchrone journalise et rend l echec sans coincer`() = runTest {
        val logs = mutableListOf<String>()
        val player = SpeechPlayer(tempDir(), onLog = { logs += it })

        val result = player.playQueued("faux-mp3".toByteArray())

        assertTrue(result.isFailure)
        assertEquals(1, logs.size)
        assertTrue(logs[0].startsWith("PLAYER error "))
    }

    @Test
    fun `deux playQueued concurrents - les deux rendent, pas d interblocage`() = runTest {
        val player = SpeechPlayer(tempDir())

        val a = async { player.playQueued("un".toByteArray()) }
        val b = async { player.playQueued("deux".toByteArray()) }

        // En JVM le MediaPlayer manque : les deux échouent en synchrone —
        // l'important est que les deux rendent (pas de gate coincée, pas
        // d'interblocage du verrou stop/lecture).
        assertTrue(a.await().isFailure)
        assertTrue(b.await().isFailure)
    }

    @Test
    fun `timeout de preparation borne sous le chien de garde bus`() {
        assertTrue(PREPARE_TIMEOUT_MS > 0)
        assertTrue(PREPARE_TIMEOUT_MS < 20_000L)
    }
}
