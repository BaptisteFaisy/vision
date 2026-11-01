package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.vm.FakeMessageDao
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.audio.SentenceChunker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attente et reprise du chat (SPEC v4 § 9, seule file restante) : quand un
 * niveau plus prioritaire coupe la réponse (danger, rappel GPS, parole
 * user), le reste attend et reprend depuis la coupure — jamais depuis le
 * début, jamais perdu. Une nouvelle réponse écrase l'attente.
 */
class ChatWaitResumeTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)

    private fun playback(
        synthesize: suspend (String) -> ByteArray = { it.toByteArray() },
        playSlice: suspend (ByteArray) -> Result<PlaybackEnd> = { Result.success(PlaybackEnd.Done) },
    ): IseePlayback = IseePlayback(
        dao = dao,
        media = media,
        player = SpeechPlayer(File(tempDir(), "play")),
        update = { fn -> state.update(fn) },
        synthesize = synthesize,
        playSlice = playSlice,
    )

    @Test
    fun `rang — le chat cede aux niveaux 0 a 8 seulement`() {
        assertEquals("rang du chat", 9, ChatWaitResume.LEVEL)
        (0..8).forEach { level ->
            assertTrue("niveau $level fait attendre", ChatWaitResume.yieldsTo(level))
        }
        assertFalse("son rang ne coupe pas", ChatWaitResume.yieldsTo(9))
        (10..12).forEach { level ->
            assertFalse("niveau $level ne coupe pas", ChatWaitResume.yieldsTo(level))
        }
    }

    @Test
    fun `file — hold puis takeDue rend depuis la coupure, une seule fois`() {
        val waiting = ChatWaitResume()

        waiting.hold(7L, listOf("deuxième.", "troisième."))

        assertTrue(waiting.hasPending())
        val due = waiting.takeDue()
        assertEquals(7L, due?.rowId)
        assertEquals(listOf("deuxième.", "troisième."), due?.remaining)
        assertNull("une seule reprise", waiting.takeDue())
        assertFalse(waiting.hasPending())
    }

    @Test
    fun `file — une nouvelle attente ecrase l'ancienne, clear jette tout`() {
        val waiting = ChatWaitResume()

        waiting.hold(1L, listOf("vieille."))
        waiting.hold(2L, listOf("fraîche."))

        assertEquals("la plus fraîche gagne", 2L, waiting.peekDue()?.rowId)
        waiting.clear()
        assertFalse(waiting.hasPending())
    }

    @Test
    fun `danger — coupe puis reprise depuis la tranche interrompue`() = runBlocking {
        val parts = SentenceChunker.chunk(LONG_ANSWER)
        assertEquals("prémisse : 3 tranches", 3, parts.size)
        val row = seedRow()
        val played = mutableListOf<ByteArray>()
        var plays = 0
        val danger = playback(
            // 1re tranche entendue, danger coupe la 2e (player.stop), puis silence.
            playSlice = { bytes ->
                plays++
                played += bytes
                if (plays == 2) Result.success(PlaybackEnd.Stopped) else Result.success(PlaybackEnd.Done)
            },
        )

        danger.speakStreaming(row, LONG_ANSWER)

        assertTrue("le reste attend", danger.hasPendingSpeech())
        played.clear()
        assertTrue("reprise jouée", danger.speakPending())
        assertEquals("reprise depuis la coupure (tranches 2 et 3)", 2, played.size)
        assertTrue(played[0].contentEquals(parts[1].toByteArray()))
        assertTrue(played[1].contentEquals(parts[2].toByteArray()))
        assertFalse("attente consommée", danger.hasPendingSpeech())
        val saved = media.replyAudioFile(dao.byId(row.id) ?: row)?.readBytes()
        val expected = parts.fold(ByteArray(0)) { acc, part -> acc + part.toByteArray() }
        assertTrue("MP3 complet après reprise", saved?.contentEquals(expected) == true)
        assertNull("état parlant rendu", state.value.speakingEntryId)
    }

    @Test
    fun `gps — rappel coupe tard puis reprise de la fin seule`() = runBlocking {
        val parts = SentenceChunker.chunk(LONG_ANSWER)
        val row = seedRow()
        val played = mutableListOf<ByteArray>()
        var plays = 0
        val gps = playback(
            // Deux tranches entendues, le rappel GPS coupe la 3e, puis silence.
            playSlice = { bytes ->
                plays++
                played += bytes
                if (plays == 3) Result.success(PlaybackEnd.Stopped) else Result.success(PlaybackEnd.Done)
            },
        )

        gps.speakStreaming(row, LONG_ANSWER)

        assertTrue("la fin attend", gps.hasPendingSpeech())
        played.clear()
        assertTrue(gps.speakPending())
        assertEquals("seule la fin reprend", 1, played.size)
        assertTrue(played[0].contentEquals(parts[2].toByteArray()))
        assertFalse(gps.hasPendingSpeech())
        assertNull("état parlant rendu", state.value.speakingEntryId)
    }

    @Test
    fun `parole user — annulation en lecture met en attente puis reprend`() = runBlocking {
        val parts = SentenceChunker.chunk(LONG_ANSWER)
        val inSecondPlay = CompletableDeferred<Unit>()
        val played = mutableListOf<ByteArray>()
        var plays = 0
        var holdGate = true
        val held = playback(
            playSlice = { bytes ->
                plays++
                played += bytes
                if (plays == 2 && holdGate) {
                    // Lecture en cours quand la parole user annule la réponse en vol.
                    holdGate = false
                    inSecondPlay.complete(Unit)
                    CompletableDeferred<Result<PlaybackEnd>>().await()
                } else {
                    Result.success(PlaybackEnd.Done)
                }
            },
        )
        val job = launch { held.speakStreaming(seedRow(), LONG_ANSWER) }
        withTimeout(2_000) { inSecondPlay.await() }
        job.cancelAndJoin()

        assertTrue("annulée en lecture : le reste attend", held.hasPendingSpeech())
        played.clear()
        assertTrue(held.speakPending())
        assertEquals("reprise depuis la coupure", 2, played.size)
        assertTrue(played[0].contentEquals(parts[1].toByteArray()))
        assertTrue(played[1].contentEquals(parts[2].toByteArray()))
        assertFalse(held.hasPendingSpeech())
        assertNull("état parlant rendu", state.value.speakingEntryId)
    }

    @Test
    fun `nouvelle reponse — ecrase l'attente, pas de double file`() = runBlocking {
        var armed = true
        val cut = playback(
            // Première réponse coupée dès sa 1re tranche, puis silence.
            playSlice = { bytes ->
                if (armed) Result.success(PlaybackEnd.Stopped) else Result.success(PlaybackEnd.Done)
            },
        )

        cut.speakStreaming(seedRow(), LONG_ANSWER)
        assertTrue("attente posée par la coupe", cut.hasPendingSpeech())

        armed = false
        cut.speakStreaming(seedRow(), "Autre réponse courte.")
        assertFalse("nouvelle réponse : attente écrasée", cut.hasPendingSpeech())
        assertFalse("reprise à vide ne joue rien", cut.speakPending())
    }

    @Test
    fun `reprise — sans attente rien ne se synthetise ni ne se joue`() = runBlocking {
        var synth = 0
        var played = 0
        val idle = playback(
            synthesize = { synth++; it.toByteArray() },
            playSlice = { played++; Result.success(PlaybackEnd.Done) },
        )

        assertFalse(idle.speakPending())
        assertEquals(0, synth)
        assertEquals(0, played)
    }

    @Test
    fun `reprise — bulle disparue, attente jetee sans lecture`() = runBlocking {
        val row = seedRow()
        var plays = 0
        val cut = playback(
            playSlice = { bytes ->
                plays++
                if (plays == 2) Result.success(PlaybackEnd.Stopped) else Result.success(PlaybackEnd.Done)
            },
        )
        cut.speakStreaming(row, LONG_ANSWER)
        assertTrue(cut.hasPendingSpeech())
        dao.deleteById(row.id)

        assertFalse("bulle disparue : rien à reprendre", cut.speakPending())
        assertFalse(cut.hasPendingSpeech())
        assertEquals("aucune lecture de reprise", 2, plays)
    }

    private fun seedRow(): MessageMultimedia =
        dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))

    private fun tempDir(): File =
        Files.createTempDirectory("isee-chat-wait").toFile().apply { deleteOnExit() }

    private companion object {
        /** Assez long pour 3 tranches : phrase courte, bloc de 1 200, chute. */
        val LONG_ANSWER = "Première phrase. " + "y".repeat(1_200) + "."
    }
}
