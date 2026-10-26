package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.live.LiveNarrator
import dev.sam3vision.core.net.LiveLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Narration continue du direct : une frame sur deux secondes part au LLM vision avec les
 * objets suivis et la description précédente, qui dit ce qui **change**. Cadence et « une
 * seule en vol » sont tenues par [LiveNarrator] — la caméra n'attend jamais le réseau, et
 * la narration marche même sans détecteur SAM.
 *
 * Extrait pour que l'état d'avancement (`narrationBusy`, `narration`, `narrationError`)
 * se teste sans ViewModel ni Android.
 */
class LiveNarration(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LiveUiState>,
    /** Objets de la frame courante, cités au modèle ; vide tant qu'aucune frame. */
    private val currentLabels: () -> List<String>,
) {

    /** Narrateur de la séance en cours ; `null` = direct arrêté. */
    private var narrator: LiveNarrator? = null

    /** Nouvelle séance : le narrateur reçoit le client LLM des réglages du moment. */
    fun open(client: LiveLlmClient) {
        narrator = LiveNarrator(client)
    }

    /** Arrêt du direct : plus aucune frame ne part au LLM. */
    fun close() {
        narrator = null
    }

    /** Frame venue de la caméra : envoyée seulement si la cadence du narrateur le permet. */
    fun narrate(jpegB64: String) {
        val active = narrator ?: return
        if (!active.due()) return
        val labels = currentLabels()
        state.update { it.copy(narrationBusy = true) }
        scope.launch {
            val result = active.narrate(jpegB64, labels)
            state.update { current ->
                when {
                    result == null -> current.copy(narrationBusy = false)
                    result.isFailure -> current.copy(
                        narrationBusy = false,
                        narrationError = result.exceptionOrNull()?.message ?: "analyse LLM impossible",
                    )
                    else -> current.copy(
                        narration = result.getOrNull().orEmpty(),
                        narrationBusy = false,
                        narrationError = null,
                    )
                }
            }
        }
    }
}