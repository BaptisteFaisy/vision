package dev.sam3vision.app.live

import dev.sam3vision.app.LLM_ABSENT_LINE
import dev.sam3vision.app.LiveMemoryState
import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.NO_FRAME_LINE
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Questions sur le direct : la dernière image part au LLM sans dépendre de SAM, une
 * question à la fois, et l'état (`asking`, `answer`, `error`) suit exactement ce que
 * l'écran affiche.
 */
class LiveAskSessionTest {

    @Test
    fun `question vide ou blanche ignoree`() = runTest {
        val llm = FakeLlm(Result.success("peu importe"))
        val (session, state) = harness(this, llm)
        session.ask("   ")
        advanceUntilIdle()
        assertEquals(0, llm.calls)
        assertFalse(state.value.memory.asking)
    }

    @Test
    fun `sans image on explique au lieu d appeler le llm`() = runTest {
        val llm = FakeLlm(Result.success("peu importe"))
        val (session, state) = harness(this, llm, jpeg = "")
        session.ask("qu'y a-t-il devant ?")
        advanceUntilIdle()
        assertEquals(NO_FRAME_LINE, state.value.memory.error)
        assertEquals(0, llm.calls)
        assertFalse(state.value.memory.asking)
    }

    @Test
    fun `sans cle llm on renvoie vers les reglages`() = runTest {
        val llm = FakeLlm(Result.success("peu importe"))
        val (session, state) = harness(this, llm, settings = Settings.Default)
        session.ask("qu'y a-t-il devant ?")
        advanceUntilIdle()
        assertEquals(LLM_ABSENT_LINE, state.value.memory.error)
        assertEquals(0, llm.calls)
        assertFalse(state.value.memory.asking)
    }

    @Test
    fun `la reponse arrive dans l etat et la question est nettoyee`() = runTest {
        val llm = FakeLlm(Result.success("Une personne à gauche."))
        val (session, state) = harness(this, llm)
        session.ask("  où est la personne ?  ")
        assertTrue("le panneau doit montrer l'attente", state.value.memory.asking)
        advanceUntilIdle()
        assertFalse(state.value.memory.asking)
        assertEquals("où est la personne ?", state.value.memory.question)
        assertEquals("Une personne à gauche.", state.value.memory.answer)
        assertNull(state.value.memory.error)
        assertEquals(1, llm.calls)
    }

    @Test
    fun `un echec du llm remonte son message`() = runTest {
        val llm = FakeLlm(Result.failure(IllegalStateException("réseau indisponible")))
        val (session, state) = harness(this, llm)
        session.ask("qu'y a-t-il devant ?")
        advanceUntilIdle()
        assertEquals("réseau indisponible", state.value.memory.error)
        assertFalse(state.value.memory.asking)
        assertNull(state.value.memory.answer)
    }

    @Test
    fun `une deuxieme question pendant la premiere est ignoree`() = runTest {
        val llm = FakeLlm(Result.success("réponse"))
        val llmState = MutableStateFlow(LiveUiState(memory = LiveMemoryState(asking = true)))
        val (session, _) = harness(this, llm, state = llmState)
        session.ask("deuxième question")
        advanceUntilIdle()
        assertEquals(0, llm.calls)
    }

    @Test
    fun `l historique est conserve puis remis a zero par reset`() = runTest {
        val llm = FakeLlm(Result.success("réponse"))
        val (session, _) = harness(this, llm)
        session.ask("première")
        advanceUntilIdle()
        session.ask("seconde")
        advanceUntilIdle()
        assertEquals("l'historique transporte le premier échange", listOf(0, 2), llm.historySizes)
        session.reset()
        session.ask("troisième")
        advanceUntilIdle()
        assertEquals(listOf(0, 2, 0), llm.historySizes)
    }

    @Test
    fun `un mot maison seul relance sam sans appeler le llm`() = runTest {
        val llm = FakeLlm(Result.success("ne doit pas servir"))
        val restarts = mutableListOf<List<String>>()
        val (session, state) = harness(this, llm, state = homeState(), onRestart = restarts::add)
        session.ask("clés")
        advanceUntilIdle()
        assertEquals(listOf(listOf("keys")), restarts)
        assertEquals(0, llm.calls)
        assertFalse(state.value.memory.asking)
        assertNull(state.value.memory.answer)
    }

    @Test
    fun `la chasse sam n exige ni image gardee ni cle llm`() = runTest {
        val llm = FakeLlm(Result.success("ne doit pas servir"))
        val restarts = mutableListOf<List<String>>()
        val (session, _) = harness(
            this, llm, state = homeState(), jpeg = "",
            settings = Settings.Default, onRestart = restarts::add,
        )
        session.ask("télécommande")
        advanceUntilIdle()
        assertEquals(listOf(listOf("tv remote")), restarts)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `une phrase part au llm meme en mode maison`() = runTest {
        val llm = FakeLlm(Result.success("Les clés sont à gauche."))
        val restarts = mutableListOf<List<String>>()
        val (session, _) = harness(this, llm, state = homeState(), onRestart = restarts::add)
        session.ask("où sont les clés ?")
        advanceUntilIdle()
        assertTrue(restarts.isEmpty())
        assertEquals(1, llm.calls)
    }

    @Test
    fun `un mot inconnu part au llm meme en mode maison`() = runTest {
        val llm = FakeLlm(Result.success("Je ne vois pas de doudou."))
        val restarts = mutableListOf<List<String>>()
        val (session, _) = harness(this, llm, state = homeState(), onRestart = restarts::add)
        session.ask("doudou")
        advanceUntilIdle()
        assertTrue(restarts.isEmpty())
        assertEquals(1, llm.calls)
    }

    @Test
    fun `un mot maison hors mode maison part au llm`() = runTest {
        val llm = FakeLlm(Result.success("réponse"))
        val street = MutableStateFlow(LiveUiState(prompts = LivePrompts.default, samEnabled = true))
        val restarts = mutableListOf<List<String>>()
        val (session, _) = harness(this, llm, state = street, onRestart = restarts::add)
        session.ask("clés")
        advanceUntilIdle()
        assertTrue(restarts.isEmpty())
        assertEquals(1, llm.calls)
    }

    @Test
    fun `un mot maison sam coupe part au llm`() = runTest {
        val llm = FakeLlm(Result.success("réponse"))
        val visionOnly = MutableStateFlow(
            LiveUiState(prompts = HomeModes.ESSENTIELS.prompts, samEnabled = false),
        )
        val restarts = mutableListOf<List<String>>()
        val (session, _) = harness(this, llm, state = visionOnly, onRestart = restarts::add)
        session.ask("clés")
        advanceUntilIdle()
        assertTrue(restarts.isEmpty())
        assertEquals(1, llm.calls)
    }

    @Test
    fun `l ancien ordre picto part au llm`() = runTest {
        val fakes = pictoFakes()
        fakes.session.ask("fais un pictogramme de ces médics")
        advanceUntilIdle()
        assertEquals(1, fakes.llm.calls)
        assertTrue(fakes.restarts.isEmpty())
        assertTrue(fakes.store.list().isEmpty())
        assertTrue(fakes.spoken.isEmpty())
    }

    @Test
    fun `le mot chasse guide la voix et pose l overlay`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()))
        fakes.store.save("medics", "png".toByteArray(), testTemplate())
        fakes.session.reset()
        advanceUntilIdle()
        fakes.session.ask("médicament")
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        val hits = fakes.state.value.pictoOverlay?.hits.orEmpty()
        assertEquals(listOf("medics"), hits.map { it.label })
        assertEquals(1, fakes.spoken.size)
        assertTrue("guide parlé : ${fakes.spoken}", fakes.spoken.single().startsWith("Medics "))
        assertEquals(fakes.spoken.single(), fakes.state.value.spoken)
        assertEquals(0, fakes.llm.calls)
        assertTrue(fakes.restarts.isEmpty())
    }

    @Test
    fun `sans chasse les reperes restent muets mais affiches`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()))
        fakes.store.save("medics", "png".toByteArray(), testTemplate())
        fakes.session.reset()
        advanceUntilIdle()
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(listOf("medics"), fakes.state.value.pictoOverlay?.hits.orEmpty().map { it.label })
        assertTrue(fakes.spoken.isEmpty())
    }

    @Test
    fun `le picto passe avant sam, sam reste le repli`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()))
        fakes.store.save("medics", "png".toByteArray(), testTemplate())
        fakes.session.reset()
        advanceUntilIdle()
        fakes.session.ask("médicament")
        advanceUntilIdle()
        assertTrue("picto prioritaire, pas de relance SAM", fakes.restarts.isEmpty())
        fakes.session.ask("clés")
        advanceUntilIdle()
        assertEquals(listOf(listOf("keys")), fakes.restarts)
    }

    @Test
    fun `les medicaments au pluriel guide aussi`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()))
        fakes.store.save("medics", "png".toByteArray(), testTemplate())
        fakes.session.reset()
        advanceUntilIdle()
        fakes.session.ask("les médicaments")
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(1, fakes.spoken.size)
        assertEquals(0, fakes.llm.calls)
    }

    @Test
    fun `reset coupe le guidage`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()))
        fakes.store.save("medics", "png".toByteArray(), testTemplate())
        fakes.session.reset()
        advanceUntilIdle()
        fakes.session.ask("médicament")
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(1, fakes.spoken.size)
        fakes.session.reset()
        advanceUntilIdle()
        fakes.session.onLiveFrame(200, 200, 2_000L)
        advanceUntilIdle()
        assertEquals("guidage coupé, pas de nouvelle phrase", 1, fakes.spoken.size)
    }

    @Test
    fun `demo picto affiche les faux reperes sans llm ni image`() = runTest {
        val fakes = pictoFakes(jpeg = "")
        fakes.session.ask("démo picto")
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(listOf("Démo picto activée."), fakes.spoken)
        val hits = fakes.state.value.pictoOverlay?.hits.orEmpty()
        assertEquals(listOf("wall", "window"), hits.map { it.label })
        assertEquals(0, fakes.llm.calls)
        assertTrue(fakes.restarts.isEmpty())
    }

    @Test
    fun `chasse wall guide vers la demo`() = runTest {
        val fakes = pictoFakes(jpeg = "")
        fakes.session.ask("démo picto")
        fakes.session.ask("wall")
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(2, fakes.spoken.size)
        assertTrue("guide démo : ${fakes.spoken}", fakes.spoken[1].startsWith("Wall "))
        assertEquals(0, fakes.llm.calls)
    }

    @Test
    fun `demo coupee efface l overlay`() = runTest {
        val fakes = pictoFakes(jpeg = "")
        fakes.session.ask("démo picto")
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(2, fakes.state.value.pictoOverlay?.hits.orEmpty().size)
        fakes.session.ask("démo picto")
        advanceUntilIdle()
        assertEquals(
            listOf("Démo picto activée.", "Démo picto coupée."),
            fakes.spoken,
        )
        assertNull(fakes.state.value.pictoOverlay)
    }
}
