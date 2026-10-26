package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Branchement de la prédiction (T8) dans l'état du direct : le ViewModel l'appelle et
 * n'y pense plus — il ne connaît que [LivePrediction].
 *
 * Le flag éteint (défaut) rend les trois méthodes **inertes** : aucune écriture d'état,
 * aucun calcul, et l'écran reste exactement celui d'avant (les masques serveur seuls).
 */
class LivePredictionWiring(private val state: MutableStateFlow<LiveUiState>) {

    private var prediction = LivePrediction(enabled = false)

    /** Applique le réglage : le direct est relancé à chaque démarrage, le câblage suit. */
    fun reset(enabled: Boolean) {
        prediction = LivePrediction(enabled = enabled)
    }

    /** Réponse serveur absorbée : elle sert d'ancre aux boîtes extrapolées. */
    fun absorb(frame: LiveFrame, nowMs: Long, width: Int, height: Int) {
        prediction.absorb(frame, nowMs, width, height)
    }

    /**
     * Boîtes de la frame caméra, publiées dans l'état : c'est ce qui fait avancer
     * l'overlay **entre** deux réponses serveur. Rien de périmé : aucune écriture.
     */
    fun publish(nowMs: Long, width: Int, height: Int) {
        if (!prediction.enabled) return
        val layer = prediction.boxesAt(nowMs, width, height)
        if (layer.empty && state.value.prediction.empty) return
        state.update { it.copy(prediction = layer) }
    }
}
