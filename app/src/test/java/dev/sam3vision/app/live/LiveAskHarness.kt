package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Harnais partagé des tests [LiveAskSession] : LLM espion, session branchée
 * sur un magasin réel (dossier jetable) et une voix espionne. Un seul endroit
 * construit les sessions de test.
 */
internal val configured = Settings.Default.copy(llmBaseUrl = "https://llm.test", llmApiKey = "cle-test")

/** LLM espion : rend toujours [result], note l'historique envoyé. */
internal class FakeLlm(var result: Result<String>) : LlmClient {
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

internal fun harness(
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

internal fun homeState() = MutableStateFlow(
    LiveUiState(prompts = HomeModes.ESSENTIELS.prompts, samEnabled = true),
)

/** Gabarit 24px non plat (dégradé) : le matcher le reconnaît toujours. */
internal fun testTemplate() = PictoTemplate(IntArray(24 * 24) { it % 256 }, 24)

/** Frame 96px où [template] est collé en (10, 20) : corrélation 1.0. */
internal fun frameWith(template: PictoTemplate): GrayImage {
    val gray = IntArray(96 * 96) { 128 }
    for (y in 0 until template.edge) {
        for (x in 0 until template.edge) {
            gray[(20 + y) * 96 + 10 + x] = template.gray[y * template.edge + x]
        }
    }
    return GrayImage(gray, 96, 96)
}

internal class PictoFakes(
    val session: LiveAskSession,
    val state: MutableStateFlow<LiveUiState>,
    val llm: FakeLlm,
    val store: PictoStore,
    val spoken: MutableList<String>,
    val restarts: MutableList<List<String>>,
    val setJpeg: (String) -> Unit,
)

/** Session branchée sur un magasin réel (dossier jetable) et une voix espionne. */
internal fun TestScope.pictoFakes(
    frame: GrayImage? = null,
    jpeg: String = "jpeg-de-test",
): PictoFakes {
    val llm = FakeLlm(Result.success("ne doit pas servir"))
    val store = PictoStore(Files.createTempDirectory("live-picto-store").toFile())
    val spoken = mutableListOf<String>()
    val restarts = mutableListOf<List<String>>()
    val state = homeState()
    var currentJpeg = jpeg
    val session = LiveAskSession(
        scope = this,
        state = state,
        timeline = LiveTimeline(),
        latestJpeg = { currentJpeg },
        refreshSettings = {},
        settings = { configured.copy(speechLanguage = SpeechLanguage.Fr) },
        llm = { llm },
        windowMs = 30_000L,
        restart = restarts::add,
        pictoSpeak = { _, phrase -> spoken += phrase },
        io = UnconfinedTestDispatcher(),
        store = store,
        frameGray = { _, _ -> frame },
        guideAnnouncer = PositionAnnouncer(minFrames = 1, cooldownMs = 0),
    )
    return PictoFakes(session, state, llm, store, spoken, restarts) { currentJpeg = it }
}
