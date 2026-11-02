package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.vm.FakeMessageDao
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * P3a (T-3a) : le chat est toujours rendu, même quand la lecture d'une
 * tranche échoue hors annulation — sinon `chatReads` restait coincé, les
 * niveaux 10/11/12 mouraient et `onSpeechIdle` restait sans effet.
 * Même couture que [ChatWaitResumeTest] (réponse jouée) + [Fix1BusReleaseTest]
 * (porte-parole faux, repli 12 accepté ensuite).
 */
class SilenceP3aPlaybackTest {

    private class FakeSpeaker : VoiceBusSpeaker {
        val said = mutableListOf<Pair<Int, String>>()

        override fun idle(): Boolean = true

        override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
            said += level to text
        }

        override fun cut() = Unit
    }

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)
    private val speaker = FakeSpeaker()

    @Before
    fun brancher() {
        VoiceBus.reset()
        VoiceBus.onIdle = null
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.onIdle = null
        VoiceBus.reset()
    }

    @Test
    fun `T-3a — echec de lecture leve ET chat rendu, repli 12 accepte ensuite`() = runBlocking {
        val failing = IseePlayback(
            dao = dao,
            media = media,
            player = SpeechPlayer(File(tempDir(), "play")),
            update = { fn -> state.update(fn) },
            synthesize = { it.toByteArray() },
            playSlice = { Result.failure(IllegalStateException("boom")) },
        )
        val row = dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))

        try {
            failing.speakStreaming(row, "Une phrase courte.")
            fail("speakStreaming aurait dû lever l'échec de lecture")
        } catch (boom: IllegalStateException) {
            assertEquals("boom", boom.message)
        }

        assertNull("état parlant rendu", state.value.speakingEntryId)
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals("repli 12 accepté après l'échec", listOf(OutdoorLevel.FALLBACK to "repli"), speaker.said)
    }

    private fun tempDir(): File =
        Files.createTempDirectory("silence-p3a").toFile().apply { deleteOnExit() }
}
