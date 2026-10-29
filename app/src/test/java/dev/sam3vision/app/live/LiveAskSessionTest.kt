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
import org.junit.Assert.assertArrayEquals
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

    /** Génération d'image espionne : rend toujours [result], note photo et consigne. */
    private class FakePictoClient(var result: Result<ByteArray>) : PictoImageClient {
        var calls = 0
        var lastPhoto: ByteArray? = null
        var lastHint: String? = null

        override suspend fun editToPictogram(photoJpeg: ByteArray, hint: String): Result<ByteArray> {
            calls++
            lastPhoto = photoJpeg
            lastHint = hint
            return result
        }
    }

    /** Gabarit 24px non plat (dégradé) : le matcher le reconnaît toujours. */
    private fun testTemplate() = PictoTemplate(IntArray(24 * 24) { it % 256 }, 24)

    /** Frame 96px où [template] est collé en (10, 20) : corrélation 1.0. */
    private fun frameWith(template: PictoTemplate): GrayImage {
        val gray = IntArray(96 * 96) { 128 }
        for (y in 0 until template.edge) {
            for (x in 0 until template.edge) {
                gray[(20 + y) * 96 + 10 + x] = template.gray[y * template.edge + x]
            }
        }
        return GrayImage(gray, 96, 96)
    }

    private class PictoFakes(
        val session: LiveAskSession,
        val state: MutableStateFlow<LiveUiState>,
        val llm: FakeLlm,
        val client: FakePictoClient,
        val store: PictoStore,
        val spoken: MutableList<String>,
        val restarts: MutableList<List<String>>,
    )

    /** Session branchée sur un magasin réel (dossier jetable) et une voix espionne. */
    private fun TestScope.pictoFakes(
        frame: GrayImage? = null,
        clientResult: Result<ByteArray> = Result.success("png".toByteArray()),
        jpeg: String = "jpeg-de-test",
    ): PictoFakes {
        val llm = FakeLlm(Result.success("ne doit pas servir"))
        val client = FakePictoClient(clientResult)
        val store = PictoStore(Files.createTempDirectory("live-picto-store").toFile())
        val spoken = mutableListOf<String>()
        val restarts = mutableListOf<List<String>>()
        val state = homeState()
        val session = LiveAskSession(
            scope = this,
            state = state,
            timeline = LiveTimeline(),
            latestJpeg = { jpeg },
            refreshSettings = {},
            settings = { configured.copy(speechLanguage = SpeechLanguage.Fr) },
            llm = { llm },
            windowMs = 30_000L,
            restart = restarts::add,
            pictoSpeak = { _, phrase -> spoken += phrase },
            io = UnconfinedTestDispatcher(),
            pictoClient = { client },
            store = store,
            frameGray = { _, _ -> frame },
            decodeB64 = { it.toByteArray() },
            guideAnnouncer = PositionAnnouncer(minFrames = 1, cooldownMs = 0),
        )
        return PictoFakes(session, state, llm, client, store, spoken, restarts)
    }

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
    fun `ordre picto cree le repere et confirme sans llm`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()))
        fakes.session.ask("fais un pictogramme de ces médics")
        advanceUntilIdle()
        assertEquals(1, fakes.client.calls)
        assertEquals("medics", fakes.client.lastHint)
        assertArrayEquals("jpeg-de-test".toByteArray(), fakes.client.lastPhoto)
        assertEquals(listOf("medics"), fakes.store.list().map { it.label })
        assertEquals(
            listOf(
                "Je crée le pictogramme.",
                "Pictogramme medics prêt. Dis son nom pour être guidé.",
            ),
            fakes.spoken,
        )
        assertEquals(0, fakes.llm.calls)
        assertTrue(fakes.restarts.isEmpty())
    }

    @Test
    fun `ordre picto sans image parle echec sans generer`() = runTest {
        val fakes = pictoFakes(jpeg = "")
        fakes.session.ask("fais un pictogramme de ces médics")
        advanceUntilIdle()
        assertEquals(listOf("Création du pictogramme impossible."), fakes.spoken)
        assertEquals(0, fakes.client.calls)
        assertTrue(fakes.store.list().isEmpty())
    }

    @Test
    fun `echec muse parle echec sans stocker`() = runTest {
        val fakes = pictoFakes(
            frame = frameWith(testTemplate()),
            clientResult = Result.failure(RuntimeException("402")),
        )
        fakes.session.ask("fais un pictogramme de ces médics")
        advanceUntilIdle()
        assertEquals(
            listOf("Je crée le pictogramme.", "Création du pictogramme impossible."),
            fakes.spoken,
        )
        assertTrue(fakes.store.list().isEmpty())
        assertEquals(0, fakes.llm.calls)
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
        assertEquals(listOf("mur", "fenêtre"), hits.map { it.label })
        assertEquals(0, fakes.llm.calls)
        assertTrue(fakes.restarts.isEmpty())
    }

    @Test
    fun `mur chasse guide vers la demo`() = runTest {
        val fakes = pictoFakes(jpeg = "")
        fakes.session.ask("démo picto")
        fakes.session.ask("mur")
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(2, fakes.spoken.size)
        assertTrue("guide démo : ${fakes.spoken}", fakes.spoken[1].startsWith("Mur "))
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