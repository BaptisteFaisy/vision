package dev.sam3vision.app.vm

import dev.sam3vision.app.UiState
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * État d'écran piloté par les tests : la même mise à jour que le ViewModel, donc les
 * collaborateurs se testent sans `Application`, sans `AndroidViewModel` et sans réseau.
 */
class FakeScreen(initial: UiState = UiState()) {
    val state = MutableStateFlow(initial)
    val update: StateUpdate = { block -> state.update(block) }
    val value: UiState get() = state.value
}

/** Réglages complets : clé LLM, clé TTS et voix renseignées (voix et lecture auto actives). */
val ConfiguredSettings: Settings = Settings.Default.copy(llmApiKey = "cle-llm", ttsApiKey = "cle-tts")

/** Analyse de test : description seule, objets au choix. */
fun analysis(description: String = "une table", objects: List<SegObject> = emptyList()): Analysis =
    Analysis(description = description, tags = emptyList(), objects = objects)

/** LLM scripté : compte ses appels et rend ce que le test lui donne. */
class FakeLlm(
    var onDescribe: (List<String>) -> Result<Analysis> = { Result.success(analysis()) },
    var onAsk: (String, List<ChatTurn>) -> Result<String> = { _, _ -> Result.success("réponse") },
) : LlmClient {

    var describeCalls = 0
        private set
    var askCalls = 0
        private set

    override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> {
        describeCalls++
        return onDescribe(labels)
    }

    override suspend fun ask(
        jpegB64: String,
        question: String,
        history: List<ChatTurn>,
        context: String?,
    ): Result<String> {
        askCalls++
        return onAsk(question, history)
    }
}

/** SAM scripté : segment et `/health` indépendants, compteurs pour prouver l'absence d'appel. */
class FakeSam(
    var onSegment: Result<SegmentResult> = Result.failure(ApiError.Network()),
    var onHealth: Result<Boolean> = Result.success(true),
) : SamClient {

    var segmentCalls = 0
        private set
    var healthCalls = 0
        private set

    override suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult> {
        segmentCalls++
        return onSegment
    }

    override suspend fun health(): Result<Boolean> {
        healthCalls++
        return onHealth
    }
}