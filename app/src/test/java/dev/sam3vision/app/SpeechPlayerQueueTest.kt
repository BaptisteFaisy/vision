package dev.sam3vision.app

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File du fil audio ([SpeechPlayer]) : les lectures simples rendent aussitôt
 * (« accepté », jamais d'attente sur un appel plateforme), la fin part au
 * rappel ; les périmées ([AUDIO_FRESH_MS]) et les doublons (dernier gagnant)
 * sont jetés sans jouer, bus rendu. File pilotée au pas, horloge maîtrisée.
 */
class SpeechPlayerQueueTest {

    private class Rig {
        var nowVar = 0L
        val dir: File = Files.createTempDirectory("file-audio").toFile()
        val done: MutableList<Unit> = mutableListOf()
        val errors: MutableList<String> = mutableListOf()

        fun player(dispatcher: CoroutineDispatcher) = SpeechPlayer(
            dir,
            onComplete = { done += Unit },
            onPlaybackError = { errors += it },
            audio = dispatcher,
            nowMs = { nowVar },
        )

        fun cached(): ByteArray? = File(dir, "voice.mp3").takeIf { it.isFile }?.readBytes()
    }

    @Test
    fun `lecture simple rend aussitot, echec au rappel`() = runTest {
        val rig = Rig()
        val player = rig.player(StandardTestDispatcher(testScheduler))

        val accepted = player.playSlice("bonjour".toByteArray())

        assertTrue(accepted.isSuccess)
        assertTrue(rig.done.isEmpty())
        assertTrue(rig.errors.isEmpty())
        runCurrent()
        // En JVM, MediaPlayer est un faux qui lève : l'unité a tourné (cache
        // écrit), l'échec part au rappel d'erreur, comme en lecture réelle.
        assertEquals(1, rig.errors.size)
        assertTrue(rig.done.isEmpty())
        assertEquals("bonjour", rig.cached()?.toString(Charsets.UTF_8))
    }

    @Test
    fun `perime jette sans jouer et rend`() = runTest {
        val rig = Rig()
        val player = rig.player(StandardTestDispatcher(testScheduler))

        assertTrue(player.playSlice("bonjour".toByteArray()).isSuccess)
        rig.nowVar = AUDIO_FRESH_MS + 1_000L
        runCurrent()

        assertEquals(1, rig.done.size)
        assertTrue(rig.errors.isEmpty())
        assertEquals(null, rig.cached())
    }

    @Test
    fun `doublon coalesce, dernier joue`() = runTest {
        val rig = Rig()
        val player = rig.player(StandardTestDispatcher(testScheduler))

        assertTrue(player.playSlice("premier".toByteArray()).isSuccess)
        assertTrue(player.playSlice("dernier".toByteArray()).isSuccess)
        runCurrent()
        advanceUntilIdle()

        // Premier jeté (rendu), dernier joué (échec JVM au rappel d'erreur).
        assertEquals(1, rig.done.size)
        assertEquals(1, rig.errors.size)
        assertEquals("dernier", rig.cached()?.toString(Charsets.UTF_8))
    }

    @Test
    fun `coupe avant lecture jette`() = runTest {
        val rig = Rig()
        val player = rig.player(StandardTestDispatcher(testScheduler))

        assertTrue(player.playSlice("bonjour".toByteArray()).isSuccess)
        player.stop()
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, rig.done.size)
        assertTrue(rig.errors.isEmpty())
        assertEquals(null, rig.cached())
    }

    @Test
    fun `tranche en echec rend l echec`() = runTest {
        val rig = Rig()
        val player = rig.player(StandardTestDispatcher(testScheduler))

        val pending = async { player.playQueued("bonjour".toByteArray()) }
        runCurrent()
        val end = pending.await()

        // En JVM, MediaPlayer est un faux qui lève : l'échec remonte au gate.
        assertTrue(end.isFailure)
    }
}
