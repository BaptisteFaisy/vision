package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.ihear.IhearPlayback
import dev.sam3vision.app.ihear.IhearUiState
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le chemin de la réponse : question dictée écrite en base, réponse du LLM posée sur
 * la même ligne, puis bulles rafraîchies. Un échec affiche son motif et rend le
 * « … » : il ne laisse jamais l'écran bloqué.
 */
class IhearAnswersTest {

    private val state = MutableStateFlow(IhearUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)
    private val transcript = IhearTranscript(dao, ::update)
    private val settings = Settings.Default.copy(llmApiKey = "test-key")

    private fun answers(
        ask: suspend (String, String, List<ChatTurn>) -> Result<String>,
        synthesize: suspend (String) -> ByteArray = { ByteArray(0) },
    ): IhearAnswers = IhearAnswers(
        dao = dao,
        media = media,
        settings = { settings },
        update = ::update,
        transcript = transcript,
        synthesize = synthesize,
        playback = playback(),
        photo = shots(),
        prune = {},
        ask = ask,
    )

    @Test
    fun `question dictee — la ligne porte le texte et son audio`() = runBlocking {
        val answers = answers({ _, _, _ -> Result.success("ok") })

        val row = answers.recordQuestion(pcm(), "  où est la porte ?  ")

        assertEquals("où est la porte ?", dao.byId(row.id)?.texteUtilisateur)
        assertTrue("audio attaché", !dao.byId(row.id)?.cheminAudioUtilisateur.isNullOrBlank())
    }

    @Test
    fun `reponse du LLM — le texte s'ecrit sur la ligne et la bulle arrive a l'ecran`() = runBlocking {
        val row = dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))
        val answers = answers({ _, _, _ -> Result.success("Devant toi") })

        answers.answer(row, "question")

        assertEquals("Devant toi", dao.byId(row.id)?.texteIA)
        assertTrue("la bulle de la réponse est affichée", state.value.entries.any { it.text == "Devant toi" })
        assertFalse("le « … » est retiré", state.value.thinking)
    }

    @Test
    fun `l'historique joint a la question vient de la base`() = runBlocking {
        dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "avant", texteIA = "réponse avant"))
        var history: List<ChatTurn> = emptyList()
        val answers = answers({ _, _, turns -> history = turns; Result.success("ok") })
        val row = dao.seed(MessageMultimedia(timestamp = 2L, texteUtilisateur = "maintenant"))

        answers.answer(row, "maintenant")

        // L'historique vient de la base, question comprise (elle est déjà écrite).
        assertEquals(listOf("avant", "réponse avant", "maintenant"), history.map { it.content })
    }

    @Test
    fun `echec du LLM — motif affiche, rien d'ecrit, le « … » est retire`() = runBlocking {
        val row = dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))
        val answers = answers({ _, _, _ -> Result.failure(IllegalStateException("limite atteinte")) })

        answers.answer(row, "question")

        assertEquals("Question failed: limite atteinte", state.value.status)
        assertNull("aucune réponse inventée", dao.byId(row.id)?.texteIA)
        assertFalse("le « … » est retiré", state.value.thinking)
    }

    @Test
    fun `sans cle LLM — motif exact et aucun appel au modele`() = runBlocking {
        var called = false
        val answers = IhearAnswers(
            dao = dao,
            media = media,
            settings = { Settings.Default },
            update = ::update,
            transcript = transcript,
            synthesize = { ByteArray(0) },
            playback = playback(),
            photo = shots(),
            prune = {},
            ask = { _, _, _ -> called = true; Result.success("ok") },
        )
        val row = dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))

        answers.answer(row, "question")

        assertEquals(IhearStatusText.NO_LLM_KEY, state.value.status)
        assertFalse("aucun appel réseau", called)
    }

    @Test
    fun `voix indisponible — le texte reste affiche sans audio`() = runBlocking {
        val row = dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))
        val answers = answers({ _, _, _ -> Result.success("Devant toi") }, synthesize = { ByteArray(0) })

        answers.answer(row, "question")

        assertEquals("Devant toi", dao.byId(row.id)?.texteIA)
        assertNull("aucun audio en cache", dao.byId(row.id)?.cheminAudioElevenLabs)
    }

    private fun update(fn: (IhearUiState) -> IhearUiState) {
        state.value = fn(state.value)
    }

    private fun playback(): IhearPlayback = IhearPlayback(
        dao = dao,
        media = media,
        player = SpeechPlayer(File(tempDir(), "play")),
        state = { state.value },
        update = ::update,
        synthesize = { ByteArray(0) },
        replayIntro = {},
    )

    private fun shots(): IhearPhotoShot = IhearPhotoShot(
        media = media,
        camera = { null },
        state = { state.value },
        settings = { settings },
        update = ::update,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** PCM muet de 100 ms (16 kHz, 16 bits, mono) : l'encodeur MP3 a de quoi écrire. */
    private fun pcm(): ByteArray = ByteArray(3_200)

    private fun tempDir(): File =
        Files.createTempDirectory("ihear-vm-answers").toFile().apply { deleteOnExit() }
}