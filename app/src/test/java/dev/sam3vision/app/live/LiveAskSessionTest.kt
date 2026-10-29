package dev.sam3vision.app.live

import dev.sam3vision.app.LLM_ABSENT_LINE
import dev.sam3vision.app.LiveMemoryState
import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.NO_FRAME_LINE
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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

    private val configured = Settings.Default.copy(llmBaseUrl = "https://llm.test", llmApiKey = "cle-test")

    /** LLM espion : rend toujours [result], note l'historique envoyé. */
    private class FakeLlm(var result: Result<String>) : LlmClient {
        var calls = 0
        val historySizes = mutableListOf<Int>()

        override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> =
            error("describe n'est pas utilisé par les questions")

        override suspend fun ask(
            jpegB64: String,
            question: String,
            history: List<ChatTurn>,
            context: String?,
        ): Result<String> {
            calls++
            historySizes += history.size
            return result
        }
    }

    private fun harness(
        scope: TestScope,
        llm: LlmClient,
        state: MutableStateFlow<LiveUiState> = MutableStateFlow(LiveUiState()),
        jpeg: String = "jpeg-de-test",
        settings: Settings = configured,
        onRestart: (List<String>) -> Unit = {},
    ): Pair<LiveAskSession, MutableStateFlow<LiveUiState>> {
        var current = settings
        val session = LiveAskSession(
            scope = scope,
            state = state,
            timeline = LiveTimeline(),
            latestJpeg = { jpeg },
            refreshSettings = { current = settings },
            settings = { current },
            llm = { llm },
            windowMs = 30_000L,
            restart = onRestart,
        )
        return session to state
    }

    private fun homeState() = MutableStateFlow(
        LiveUiState(prompts = HomeModes.ESSENTIELS.prompts, samEnabled = true),
    )

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
}