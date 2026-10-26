package dev.sam3vision.app.vm

import dev.sam3vision.app.UiState
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AskUseCase

/**
 * Le chat sur la photo courante : question → réponse du LLM → historique + voix.
 *
 * Rien ne part sans photo, sans question ou sans clé LLM (`UiState.canAsk` dit la même
 * chose à l'écran) : le refus est local, l'état ne bouge pas. Un échec rend la main
 * avec un motif lisible, jamais un « … » qui reste.
 */
class AppChat(
    private val state: () -> UiState,
    private val update: StateUpdate,
    private val llm: (Settings) -> LlmClient,
    private val speak: (String) -> Unit,
) {

    /** Pose [question] sur la photo courante ; sans photo, question ou clé : rien. */
    suspend fun ask(question: String) {
        val snapshot = state()
        if (snapshot.photoB64.isBlank() || question.isBlank() || !snapshot.settings.llmConfigured) return
        update { it.copy(asking = true, statusLine = null) }
        val useCase = AskUseCase(llm(snapshot.settings))
        useCase.invoke(snapshot.photoB64, question.trim(), snapshot.chat).fold(
            onSuccess = { text -> answered(useCase, question.trim(), text, snapshot.settings) },
            onFailure = { error ->
                update { it.copy(asking = false, statusLine = AppStatusText.questionFailed(error)) }
            },
        )
    }

    /** Réponse reçue : historique à jour, puis lecture auto si la voix est réglée. */
    private fun answered(useCase: AskUseCase, question: String, text: String, settings: Settings) {
        update { it.copy(chat = useCase.nextHistory(it.chat, question, text), asking = false) }
        if (settings.ttsAutoRead) speak(text)
    }
}