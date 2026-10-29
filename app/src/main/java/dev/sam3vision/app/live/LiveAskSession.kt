package dev.sam3vision.app.live

import dev.sam3vision.app.LLM_ABSENT_LINE
import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.NO_FRAME_LINE
import dev.sam3vision.app.focusLabel
import dev.sam3vision.app.focusWindow
import dev.sam3vision.app.timelineContextOrNull
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AskUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Questions sur le direct : la **dernière image part tout de suite au LLM**, sans
 * dépendre de SAM (ni session, ni journal, ni GPU). L'historique de la conversation est
 * conservé d'une question à l'autre — jusqu'à la fin de la séance : [reset] l'oublie, et
 * avec lui le journal ([LiveTimeline]) que ces questions consultent. C'est le point
 * unique où la mémoire du direct s'efface, appelé au démarrage **et** à l'arrêt : sinon
 * l'écran d'un direct arrêté montre encore, et répond encore sur, le direct précédent.
 *
 * Les réglages sont relus à chaque question ([reloadSettings]) : une clé saisie pendant
 * le direct s'applique tout de suite. C'est aussi pourquoi la classe ne garde pas de
 * copie des réglages — elle lit [settings], la source unique du ViewModel.
 */
class LiveAskSession(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LiveUiState>,
    private val timeline: LiveTimeline,
    /** Dernière frame gardée pour les questions ("" tant qu'aucune). */
    private val latestJpeg: () -> String,
    /** Relit **et applique** les réglages chiffrés (le ViewModel garde la source unique). */
    private val refreshSettings: suspend () -> Unit,
    private val settings: () -> Settings,
    private val llm: (Settings) -> LlmClient,
    /** Fenêtre du journal envoyée au LLM : 30 s suffisent pour « il y a 5 s ». */
    private val windowMs: Long,
    /** Chasse SAM d'un mot maison seul : relance le direct, jamais le LLM. */
    private val restart: (List<String>) -> Unit = {},
) {

    /** Historique envoyé au modèle, remis à zéro à chaque nouvelle séance. */
    private var history: List<ChatTurn> = emptyList()

    /** Fin de séance (démarrage suivant **ou** arrêt) : conversation et journal repartent de zéro. */
    fun reset() {
        history = emptyList()
        timeline.clear()
    }

    /**
     * Question posée à l'écrit ou à la voix (« Parler ») : une seule à la fois.
     * Un mot maison seul, mode maison et SAM allumé : chasse SAM directe (les
     * positions parlées répondent), sans frame gardée, sans clé LLM, sans appel.
     */
    fun ask(question: String) {
        val hunt = HomeModes.voiceConcept(question, state.value.prompts, state.value.samEnabled)
        if (hunt != null) {
            restart(listOf(hunt))
            return
        }
        val text = question.trim()
        if (text.isEmpty() || state.value.memory.asking) return
        val jpeg = latestJpeg()
        if (jpeg.isBlank()) {
            state.update { it.copy(memory = it.memory.copy(error = NO_FRAME_LINE)) }
            return
        }
        state.update {
            it.copy(memory = it.memory.copy(asking = true, question = text, answer = null, error = null))
        }
        scope.launch {
            refreshSettings()
            if (!settings().llmConfigured) {
                state.update { it.copy(memory = it.memory.copy(asking = false, error = LLM_ABSENT_LINE)) }
                return@launch
            }
            askWithContext(text, jpeg, state.value.memory.focusSeconds)
        }
    }

    /** Envoi au LLM : image + journal (s'il y a des pistes), puis mise à jour de l'état. */
    private suspend fun askWithContext(question: String, jpegB64: String, focusSeconds: Int) {
        val now = System.currentTimeMillis()
        val global = timeline.recent(now, windowMs)
        val window = focusWindow(now, focusSeconds)
        val focus = timeline.summary(window.first, window.last)
        val context = timelineContextOrNull(
            global,
            focus,
            focusLabel(focusSeconds, settings().speechLanguage),
            settings().speechLanguage,
        )
        val useCase = AskUseCase(llm(settings()))
        useCase.invoke(jpegB64, question, history, context).fold(
            onSuccess = { answer ->
                history = useCase.nextHistory(history, question, answer)
                state.update { it.copy(memory = it.memory.copy(asking = false, answer = answer)) }
            },
            onFailure = { error ->
                state.update {
                    it.copy(memory = it.memory.copy(asking = false, error = error.message ?: "réponse impossible"))
                }
            },
        )
    }
}