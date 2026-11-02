package dev.sam3vision.app

import java.io.File
import java.nio.file.Files
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
}
