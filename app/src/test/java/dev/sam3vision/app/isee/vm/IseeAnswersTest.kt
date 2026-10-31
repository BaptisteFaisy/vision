package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
 * un contexte oublié joindrait une vieille frame à la question suivante.
 *
 * Socle (état, base, média, caméra, fabriques) : [IseeAnswersTestBase].
 */
class IseeAnswersTest : IseeAnswersTestBase() {

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

        // Zéro historique : ni les messages précédents, ni rien de la base.
        assertTrue("aucun tour envoyé au LLM", history?.isEmpty() == true)
    }

    @Test
    fun `guidage actif — la destination part en contexte, l'historique reste vide`() = runBlocking {
        var history: List<ChatTurn>? = null
        var context: String? = "non appelé"
        val answers = answers(
            { _, _, turns -> history = turns; Result.success("ok") },
            destination = "la gare",
            onContext = { context = it },
        )

        answers.answer(seedRow(), "c'est encore loin ?")

        assertEquals("Active GPS destination: \"la gare\".", context)
        assertTrue("aucun tour envoyé au LLM", history?.isEmpty() == true)
    }

    @Test
    fun `hors guidage — aucun contexte`() = runBlocking {
        var context: String? = "non appelé"
        val answers = answers({ _, _, _ -> Result.success("ok") }, onContext = { context = it })

        answers.answer(seedRow(), "question")

        assertNull("aucun contexte hors guidage", context)
    }

    @Test
    fun `destination en francais — la ligne suit la langue de la voix`() = runBlocking {
        var context: String? = null
        val answers = answers(
            { _, _, _ -> Result.success("ok") },
            destination = "la gare",
            onContext = { context = it },
            testSettings = settings.copy(speechLanguage = SpeechLanguage.Fr),
        )

        answers.answer(seedRow(), "question")

        assertEquals("Destination GPS en cours : « la gare ».", context)
    }

    @Test
    fun `fournisseur de destination qui jette — la question part sans contexte`() = runBlocking {
        var context: String? = "non appelé"
        val answers = IseeAnswers(
            media = media,
            settings = { settings },
            update = ::update,
            transcript = transcript,
            playback = playback(),
            photo = shots,
            prune = {},
            destination = { error("mémoire en panne") },
            ask = { _, _, _, received -> context = received; Result.success("ok") },
        )
        val row = seedRow()

        answers.answer(row, "question")

        assertNull("pas de contexte", context)
        assertEquals("la question part quand même", "ok", dao.byId(row.id)?.texteIA)
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
    fun `succes — le contexte photo est relache`() = runBlocking {
        val held = photoShot(encode = { "QUJD" })
        val answers = answers({ _, _, _ -> Result.success("Devant toi") }, photo = held)
        held.captureForHold()
        assertTrue("contexte en mains", imageHeld(held))

        answers.answer(seedRow(), "question")

        camera.accept = false
        assertFalse("contexte relâché après la réponse", imageHeld(held))
    }

    @Test
    fun `echec du LLM — le contexte photo est relache`() = runBlocking {
        val held = photoShot(encode = { "QUJD" })
        val answers = answers({ _, _, _ -> Result.failure(IllegalStateException("409")) }, photo = held)
        held.captureForHold()
        assertTrue("contexte en mains", imageHeld(held))

        answers.answer(seedRow(), "question")

        camera.accept = false
        assertFalse("contexte relâché malgré l'échec", imageHeld(held))
    }

    @Test
    fun `sans cle LLM — le contexte photo est relache`() = runBlocking {
        val held = photoShot(encode = { "QUJD" })
        held.captureForHold()
        assertTrue("contexte en mains", imageHeld(held))

        answersWithoutKey(photo = held).answer(seedRow(), "question")

        camera.accept = false
        assertFalse("contexte relâché même sans clé", imageHeld(held))
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
        shots.captureForHold()

        answers.answer(seedRow(), "question")

        assertEquals("", image)
    }

    @Test
    fun `photo lisible — l'image part avec la question`() = runBlocking {
        var image: String? = null
        val readable = photoShot(encode = { "QUJD" })
        val answers = answers({ img, _, _ -> image = img; Result.success("ok") }, photo = readable)
        readable.captureForHold()

        answers.answer(seedRow(), "question")

        assertEquals("l'image de la frame est jointe", "QUJD", image)
    }

    @Test
    fun `pendant la question — l'etape Asking est affichee`() = runBlocking {
        val gate = CompletableDeferred<Result<String>>()
        val answers = answers({ _, _, _ -> gate.await() })
        val job = launch { answers.answer(seedRow(), "question") }
        withTimeout(2_000) { while (state.value.status != IseeStatusText.STEP_ASKING) delay(5) }

        assertTrue("le « … » est affiché pendant l'étape", state.value.thinking)

        gate.complete(Result.success("ok"))
        job.join()
    }

    @Test
    fun `question qui depasse le plafond — motif d'etape, puis la suivante passe`() = runBlocking {
        val gate = CompletableDeferred<Result<String>>()
        var calls = 0
        val answers = answers(
            ask = { _, _, _ -> calls++; if (calls == 1) gate.await() else Result.success("reprise") },
            askTimeoutMs = 50,
        )
        val row = seedRow()

        answers.answer(row, "question")

        assertEquals("Asking timed out — try again", state.value.status)
        assertFalse("le « … » est retiré", state.value.thinking)

        answers.answer(row, "question")

        assertEquals("la question suivante passe", "reprise", dao.byId(row.id)?.texteIA)
    }

    @Test
    fun `pendant la voix — l'etape Speaking est affichee`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val streaming = playback(
            synthesize = { byteArrayOf(1, 2) },
            playSlice = { entered.complete(Unit); release.await(); Result.success(PlaybackEnd.Done) },
        )
        val answers = IseeAnswers(
            media = media,
            settings = { settings },
            update = ::update,
            transcript = transcript,
            playback = streaming,
            photo = shots,
            prune = {},
            ask = { _, _, _, _ -> Result.success("Devant toi") },
        )
        val job = launch { answers.answer(seedRow(), "question") }
        withTimeout(2_000) { entered.await() }

        assertEquals(IseeStatusText.STEP_SPEAKING, state.value.status)

        release.complete(Unit)
        job.join()
    }

    @Test
    fun `nouvel appui pendant la question — la precedente est coupee, la suivante passe`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Result<String>>()
        val answers = answers({ _, _, _ ->
            calls.incrementAndGet()
            gate.await()
        })
        val row = seedRow()
        val first = launch { answers.answer(row, "première") }
        withTimeout(2_000) { while (calls.get() == 0) delay(5) }

        // Nouvel appui : la réponse en vol est coupée au lieu de jeter la suivante.
        answers.cancelInFlight()
        first.join()

        assertFalse("le « … » est retiré après la coupe", state.value.thinking)

        gate.complete(Result.success("reprise"))
        answers.answer(row, "seconde")

        assertEquals("la question suivante passe", "reprise", dao.byId(row.id)?.texteIA)
    }

    @Test
    fun `nouvel appui pendant la voix — la lecture est coupee, le « … » est retire`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val streaming = playback(
            synthesize = { byteArrayOf(1, 2) },
            playSlice = { entered.complete(Unit); release.await(); Result.success(PlaybackEnd.Done) },
        )
        val answers = IseeAnswers(
            media = media,
            settings = { settings },
            update = ::update,
            transcript = transcript,
            playback = streaming,
            photo = shots,
            prune = {},
            ask = { _, _, _, _ -> Result.success("Devant toi") },
        )
        val job = launch { answers.answer(seedRow(), "question") }
        withTimeout(2_000) { entered.await() }

        answers.cancelInFlight()
        job.join()

        assertFalse("le « … » est retiré après la coupe", state.value.thinking)
        assertNull("l'état parlant est rendu", state.value.speakingEntryId)
    }
}
