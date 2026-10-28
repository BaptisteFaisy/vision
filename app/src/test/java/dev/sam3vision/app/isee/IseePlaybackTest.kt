package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.vm.FakeMessageDao
import dev.sam3vision.app.media.MediaCacheStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lecture iSee : l'état parlant est posé avant la lecture et rendu sur
 * échec. Un `Result` de lecture ignoré laisserait `speakingEntryId` à vie —
 * les tenues suivantes seraient jetées (« Busy speaking ») et les annonces
 * bloquées, sans que l'écran ne dise jamais pourquoi.
 */
class IseePlaybackTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)

    private fun playback(play: suspend (ByteArray) -> Result<Unit>): IseePlayback = IseePlayback(
        dao = dao,
        media = media,
        player = SpeechPlayer(File(tempDir(), "play")),
        update = { fn -> state.update(fn) },
        synthesize = { ByteArray(0) },
        playBytes = play,
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

    private fun tempDir(): File =
        Files.createTempDirectory("isee-playback").toFile().apply { deleteOnExit() }
}
