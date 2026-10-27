package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseePlayback
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PhotoMeasure
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le chemin de la réponse : question dictée écrite en base, réponse du LLM posée sur
 * la même ligne, puis bulles rafraîchies. Un échec affiche son motif et rend le
 * « … » : il ne laisse jamais l'écran bloqué.
 *
 * La photo-contexte, elle, est **toujours** relâchée (succès, échec, clé absente) :
 * un gel oublié figeait la caméra jusqu'à la prochaine question.
 */
class IseeAnswersTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)
    private val transcript = IseeTranscript(dao, ::update)
    private val settings = Settings.Default.copy(llmApiKey = "test-key")
    private val camera = FakeCamera()
    private val shots = photoShot(encode = { null })

    private fun answers(
        ask: suspend (String, String, List<ChatTurn>) -> Result<String>,
        synthesize: suspend (String) -> ByteArray = { ByteArray(0) },
        photo: IseePhotoShot = shots,
    ): IseeAnswers = IseeAnswers(
        dao = dao,
        media = media,
        settings = { settings },
        update = ::update,
        transcript = transcript,
        synthesize = synthesize,
        playback = playback(),
        photo = photo,
        prune = {},
        ask = ask,
    )

    /** Réponses branchées sur une clé LLM absente : le contexte photo doit partir aussi. */
    private fun answersWithoutKey(
        photo: IseePhotoShot = shots,
        ask: suspend (String, String, List<ChatTurn>) -> Result<String> = { _, _, _ -> Result.success("ok") },
    ): IseeAnswers = IseeAnswers(
        dao = dao,
        media = media,
        settings = { Settings.Default },
        update = ::update,
        transcript = transcript,
        synthesize = { ByteArray(0) },
        playback = playback(),
        photo = photo,
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
    fun `aucun historique — la question part seule, meme si la base garde des messages`() = runBlocking {
        dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "avant", texteIA = "réponse avant"))
        var history: List<ChatTurn>? = null
        val answers = answers({ _, _, turns -> history = turns; Result.success("ok") })
        val row = dao.seed(MessageMultimedia(timestamp = 2L, texteUtilisateur = "maintenant"))

        answers.answer(row, "maintenant")

        // Zéro mémoire : ni les messages précédents, ni rien de la base.
        assertTrue("aucun tour envoyé au LLM", history?.isEmpty() == true)
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
        val answers = answersWithoutKey(ask = { _, _, _ -> called = true; Result.success("ok") })

        answers.answer(seedRow(), "question")

        assertEquals(IseeStatusText.NO_LLM_KEY, state.value.status)
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

    @Test
    fun `succes — le gel de la photo est relache`() = runBlocking {
        val answers = answers({ _, _, _ -> Result.success("Devant toi") })
        shots.takePhoto()
        assertTrue("la photo gelée bloque la capture", captureBlocked(shots))

        answers.answer(seedRow(), "question")

        assertFalse("le gel est relâché après la réponse", captureBlocked(shots))
    }

    @Test
    fun `echec du LLM — le gel de la photo est relache`() = runBlocking {
        val answers = answers({ _, _, _ -> Result.failure(IllegalStateException("409")) })
        shots.takePhoto()
        assertTrue("la photo gelée bloque la capture", captureBlocked(shots))

        answers.answer(seedRow(), "question")

        assertFalse("le gel est relâché malgré l'échec", captureBlocked(shots))
    }

    @Test
    fun `sans cle LLM — le gel de la photo est relache`() = runBlocking {
        shots.takePhoto()
        assertTrue("la photo gelée bloque la capture", captureBlocked(shots))

        answersWithoutKey().answer(seedRow(), "question")

        assertFalse("le gel est relâché même sans clé", captureBlocked(shots))
    }

    @Test
    fun `exception a la lecture de la reponse — motif affiche, pas de silence`() = runBlocking {
        val answers = answers({ _, _, _ -> Result.success("Devant toi") }, synthesize = { error("voix en panne") })

        answers.answer(seedRow(), "question")

        assertEquals("Question failed: voix en panne", state.value.status)
        assertFalse("le « … » est retiré", state.value.thinking)
    }

    @Test
    fun `sans photo — la question part en texte seul`() = runBlocking {
        var image: String? = null
        val answers = answers({ img, _, _ -> image = img; Result.success("ok") })

        answers.answer(seedRow(), "question")

        assertEquals("aucune image jointe", "", image)
    }

    @Test
    fun `photo illisible — texte seul, jamais une image vide`() = runBlocking {
        // Décodage raté (JVM sans codec, fichier purgé) : `imageBase64()` rend `null` et la
        // question part en texte seul — c'est ce qu'une image vide ne doit plus provoquer (400).
        var image: String? = null
        val answers = answers({ img, _, _ -> image = img; Result.success("ok") })
        shots.takePhoto()

        answers.answer(seedRow(), "question")

        assertEquals("", image)
        assertFalse("le gel est relâché", captureBlocked(shots))
    }

    @Test
    fun `photo lisible — l'image part avec la question`() = runBlocking {
        var image: String? = null
        val readable = photoShot(encode = { "QUJD" })
        val answers = answers({ img, _, _ -> image = img; Result.success("ok") }, photo = readable)
        readable.takePhoto()

        answers.answer(seedRow(), "question")

        assertEquals("l'image de la photo gelée est jointe", "QUJD", image)
    }

    @Test
    fun `deux questions en meme temps — un seul appel au modele`() = runBlocking {
        val calls = AtomicInteger()
        val inFlight = CompletableDeferred<Unit>()
        val answers = answers({ _, _, _ ->
            calls.incrementAndGet()
            inFlight.await()
            Result.success("Devant toi")
        })
        val row = seedRow()
        val first = launch { answers.answer(row, "première") }
        // Le premier appel est en vol : la seconde tenue ne doit pas en lancer un autre.
        withTimeout(2_000) { while (calls.get() == 0) delay(5) }

        answers.answer(row, "seconde")

        assertEquals(IseeStatusText.ANSWER_PENDING, state.value.status)
        inFlight.complete(Unit)
        first.join()
        assertEquals("un seul appel LLM", 1, calls.get())
    }

    private fun seedRow(): MessageMultimedia =
        dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))

    /** Vrai quand le gel empêche une nouvelle capture : c'est lui que `answer` relâche. */
    private fun captureBlocked(photo: IseePhotoShot): Boolean {
        val before = camera.demands
        photo.captureForHold()
        return camera.demands == before
    }

    private fun update(fn: (IseeUiState) -> IseeUiState) {
        state.value = fn(state.value)
    }

    private fun playback(): IseePlayback = IseePlayback(
        dao = dao,
        media = media,
        player = SpeechPlayer(File(tempDir(), "play")),
        update = ::update,
        synthesize = { ByteArray(0) },
    )

    /** Contexte photo de test : [encode] simule le décodage JPEG, absent sur JVM. */
    private fun photoShot(encode: (File) -> String?): IseePhotoShot = IseePhotoShot(
        media = media,
        camera = { camera },
        state = { state.value },
        settings = { settings },
        update = ::update,
        scope = CoroutineScope(Dispatchers.Unconfined),
        // Pastilles muettes : ces tests ne parlent que du gel et de l'image jointe.
        measure = { _, _ -> PhotoMeasure.NoObjects },
        encode = encode,
    )

    /** PCM muet de 100 ms (16 kHz, 16 bits, mono) : l'encodeur MP3 a de quoi écrire. */
    private fun pcm(): ByteArray = ByteArray(3_200)

    private fun tempDir(): File =
        Files.createTempDirectory("isee-vm-answers").toFile().apply { deleteOnExit() }

    /** Caméra factice : chaque demande de photo est comptée, écrite sur disque et réussit. */
    private class FakeCamera : IseeCameraActions {
        var demands = 0

        override val ready: Boolean = true

        override fun startRecording(file: File): Boolean = false

        override suspend fun stopRecording(): File? = null

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            demands++
            target.parentFile?.mkdirs()
            target.writeBytes(PHOTO_BYTES)
            onSaved(true)
            return true
        }

        private companion object {
            /** Quatre octets suffisent : le contenu de la photo n'est jamais décodé ici. */
            val PHOTO_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        }
    }
}