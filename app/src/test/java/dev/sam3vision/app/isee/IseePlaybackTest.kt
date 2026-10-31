package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.vm.FakeMessageDao
import dev.sam3vision.app.isee.vm.IseeStatusText
import dev.sam3vision.app.isee.vm.StepTimeout
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.audio.SentenceChunker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lecture iSee : l'état parlant est posé avant la lecture et rendu sur
 * échec. Un `Result` de lecture ignoré laisserait `speakingEntryId` à vie —
 * les annonces se croiraient bloquées, sans que l'écran ne dise jamais pourquoi.
 */
class IseePlaybackTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)

    private fun playback(
        play: suspend (ByteArray) -> Result<Unit> = { Result.success(Unit) },
        synthesize: suspend (String) -> ByteArray = { ByteArray(0) },
        playSlice: suspend (ByteArray) -> Result<PlaybackEnd> = { Result.success(PlaybackEnd.Done) },
    ): IseePlayback = IseePlayback(
        dao = dao,
        media = media,
        player = SpeechPlayer(File(tempDir(), "play")),
        update = { fn -> state.update(fn) },
        synthesize = synthesize,
        playBytes = play,
        playSlice = playSlice,
    )

    @Test
    fun `octets — la lecture en cours pose l'id et le texte`() = runBlocking {
        playback(play = { Result.success(Unit) }).speakBytes(SPEED_BUBBLE_ID, "Vitesse ×1,5", byteArrayOf(1))

        assertEquals(SPEED_BUBBLE_ID, state.value.speakingEntryId)
        assertEquals("Vitesse ×1,5", state.value.speakingText)
    }

    @Test
    fun `octets — un echec rend l'etat parlant avec son motif`() = runBlocking {
        val failure = RuntimeException("boom")

        playback(play = { Result.failure(failure) }).speakBytes(SPEED_BUBBLE_ID, "Vitesse ×1,5", byteArrayOf(1))

        assertNull(state.value.speakingEntryId)
        assertEquals("", state.value.speakingText)
        assertEquals("audio playback failed: $failure", state.value.status)
    }

    @Test
    fun `octets — un echec ne coupe pas une lecture plus recente`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val playback = playback(play = { gate.await(); Result.failure(RuntimeException("boom")) })
        val job = launch { playback.speakBytes(SPEED_BUBBLE_ID, "Vitesse ×1,5", byteArrayOf(1)) }
        // Entre la pose et l'échec, une annonce plus récente prend le lecteur.
        while (state.value.speakingEntryId != SPEED_BUBBLE_ID) kotlinx.coroutines.delay(5)
        state.update { it.copy(speakingEntryId = ANNOUNCEMENT_BUBBLE_ID, speakingText = "Turn left") }
        gate.complete(Unit)
        job.join()

        assertEquals(ANNOUNCEMENT_BUBBLE_ID, state.value.speakingEntryId)
        assertEquals("Turn left", state.value.speakingText)
    }

    @Test
    fun `derniere reponse — sans ligne répondue rien ne se joue`() = runBlocking {
        var played = false

        playback(play = { played = true; Result.success(Unit) }).speakLatestAnswer()

        assertTrue("aucune lecture sans réponse", !played)
        assertNull(state.value.speakingEntryId)
    }

    @Test
    fun `lecture en continu — les tranches partent dans l'ordre, la suivante pendant la precedente`() = runBlocking {
        val parts = SentenceChunker.chunk(LONG_ANSWER)
        assertEquals("prémisse : 3 tranches", 3, parts.size)
        val played = mutableListOf<ByteArray>()
        val synthCalls = mutableListOf<String>()
        val secondSynthGate = CompletableDeferred<Unit>()
        val streaming = playback(
            synthesize = { part ->
                synthCalls += part
                if (synthCalls.size == 2) secondSynthGate.await()
                part.toByteArray()
            },
            playSlice = { bytes -> played += bytes; Result.success(PlaybackEnd.Done) },
        )
        val job = launch { streaming.speakStreaming(seedRow(), LONG_ANSWER) }
        // La 1re tranche est jouée pendant que la 2e synthétise encore.
        withTimeout(2_000) { while (played.isEmpty()) delay(5) }
        assertEquals("une seule tranche jouée pendant la synthèse suivante", 1, played.size)
        secondSynthGate.complete(Unit)
        job.join()

        assertEquals("3 tranches jouées", 3, played.size)
        played.forEachIndexed { index, bytes ->
            assertTrue("tranche $index dans l'ordre", bytes.contentEquals(parts[index].toByteArray()))
        }
        assertEquals("3 synthèses, une par tranche", parts, synthCalls)
        assertNull("état parlant rendu", state.value.speakingEntryId)
    }

    @Test
    fun `tranches — l'etat parlant ne couvre que la lecture reelle`() = runBlocking {
        val flagsDuringPlay = mutableListOf<Long?>()
        val playGate = CompletableDeferred<Unit>()
        val synthGate = CompletableDeferred<Unit>()
        var synthCalls = 0
        var plays = 0
        val streaming = playback(
            synthesize = { part ->
                synthCalls++
                if (synthCalls == 2) synthGate.await()
                part.toByteArray()
            },
            playSlice = { bytes ->
                plays++
                flagsDuringPlay += state.value.speakingEntryId
                if (plays == 1) playGate.await()
                Result.success(PlaybackEnd.Done)
            },
        )
        val job = launch { streaming.speakStreaming(seedRow(), LONG_ANSWER) }
        withTimeout(2_000) { while (flagsDuringPlay.isEmpty()) delay(5) }
        withTimeout(2_000) { while (synthCalls < 2) delay(5) }
        playGate.complete(Unit)
        delay(100)

        assertNull("état rendu pendant la synthèse suivante (lecteur libre)", state.value.speakingEntryId)
        synthGate.complete(Unit)
        job.join()

        assertEquals("3 tranches jouées", 3, flagsDuringPlay.size)
        assertTrue("parlant pendant chaque lecture", flagsDuringPlay.all { it != null })
        assertNull("état parlant rendu", state.value.speakingEntryId)
    }

    @Test
    fun `lecture en continu — le MP3 rejouable concatene les tranches`() = runBlocking {
        val parts = SentenceChunker.chunk(LONG_ANSWER)
        val row = seedRow()
        playback(synthesize = { it.toByteArray() }).speakStreaming(row, LONG_ANSWER)

        val saved = media.replyAudioFile(dao.byId(row.id) ?: row)?.readBytes()
        val expected = parts.fold(ByteArray(0)) { acc, part -> acc + part.toByteArray() }
        assertTrue("concaténation rejouable", saved?.contentEquals(expected) == true)
    }

    @Test
    fun `texte vide — rien ne se synthetise ni ne se joue`() = runBlocking {
        var synth = 0
        var played = 0
        val streaming = playback(
            synthesize = { synth++; it.toByteArray() },
            playSlice = { played++; Result.success(PlaybackEnd.Done) },
        )

        streaming.speakStreaming(seedRow(), "   ")

        assertEquals(0, synth)
        assertEquals(0, played)
        assertNull(state.value.speakingEntryId)
        assertNull(state.value.status)
    }

    @Test
    fun `synthese qui depasse le plafond — StepTimeout et etat parlant rendu`() = runBlocking {
        val gate = CompletableDeferred<ByteArray>()
        val streaming = playback(synthesize = { gate.await() })

        val failure = runCatching { streaming.speakStreaming(seedRow(), "Devant toi.", synthTimeoutMs = 50) }
            .exceptionOrNull()

        assertTrue("l'échéance lève StepTimeout", failure is StepTimeout)
        assertEquals(IseeStatusText.STEP_SPEAKING, (failure as StepTimeout).step)
        assertNull("état parlant rendu", state.value.speakingEntryId)
    }

    @Test
    fun `lecture en echec — le motif remonte et l'etat parlant est rendu`() = runBlocking {
        val boom = RuntimeException("hp coupé")
        val streaming = playback(
            synthesize = { byteArrayOf(1) },
            playSlice = { Result.failure(boom) },
        )

        val failure = runCatching { streaming.speakStreaming(seedRow(), "Devant toi.") }.exceptionOrNull()

        assertTrue("le motif remonte (pas un timeout déguisé)", failure is RuntimeException && failure !is StepTimeout)
        assertEquals("hp coupé", failure?.message)
        assertNull("état parlant rendu", state.value.speakingEntryId)
    }

    @Test
    fun `coupe entre deux tranches — arret propre, partiel sauvegarde, sans erreur`() = runBlocking {
        val parts = SentenceChunker.chunk(LONG_ANSWER)
        var plays = 0
        val streaming = playback(
            synthesize = { it.toByteArray() },
            playSlice = { plays++; if (plays == 1) Result.success(PlaybackEnd.Done) else Result.success(PlaybackEnd.Stopped) },
        )
        val row = seedRow()

        streaming.speakStreaming(row, LONG_ANSWER)

        assertEquals("2e tranche demandée puis coupée", 2, plays)
        val saved = media.replyAudioFile(dao.byId(row.id) ?: row)?.readBytes()
        assertTrue("seule la 1re tranche est rejouable", saved?.contentEquals(parts[0].toByteArray()) == true)
        assertNull("état parlant rendu", state.value.speakingEntryId)
        assertNull("aucun motif d'erreur", state.value.status)
    }

    @Test
    fun `fin de flux — une lecture plus recente garde son id`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val streaming = playback(
            synthesize = { byteArrayOf(1) },
            playSlice = { gate.await(); Result.success(PlaybackEnd.Done) },
        )
        val job = launch { streaming.speakStreaming(seedRow(), "Devant toi.") }
        withTimeout(2_000) { while (state.value.speakingEntryId == null) delay(5) }
        state.update { it.copy(speakingEntryId = 999L) }
        gate.complete(Unit)
        job.join()

        assertEquals(999L, state.value.speakingEntryId)
    }

    private fun seedRow(): MessageMultimedia =
        dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))

    private fun tempDir(): File =
        Files.createTempDirectory("isee-playback").toFile().apply { deleteOnExit() }

    private companion object {
        /** Assez long pour 3 tranches : phrase courte, bloc de 1 200, chute. */
        val LONG_ANSWER = "Première phrase. " + "y".repeat(1_200) + "."
    }
}
