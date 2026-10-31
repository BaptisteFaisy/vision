package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.LiveProbe
import dev.sam3vision.app.isee.setCrossingActive
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.crossing.CrossingVoiceAction
import dev.sam3vision.core.crossing.CrossingVoiceCommand
import dev.sam3vision.core.live.SteeringCommand
import dev.sam3vision.core.live.SteeringText
import dev.sam3vision.core.settings.Settings

/**
 * Ordres courts d'iSee autour de la caméra : bascule du mode Traversée,
 * ordre vocal (« traverser », « crossing mode on/off ») et ordre de cap
 * (« va à gauche »). Le mode Traversée ne s'arme plus seul : ni la vision
 * ni le guidage ne le basculent, seuls la puce et l'ordre vocal le font.
 * Extrait d'[IseeViewModel] (limite 500 lignes) : la logique ne change pas,
 * elle vit simplement à côté — même motif que `ProbeWiring`, `IseeVoiceNav`
 * et sœurs.
 *
 * Les confirmations partent par le même chemin voix ([speak]) : une seule
 * source sonore, jamais une réponse par-dessus. Sans effet si la sonde est
 * déjà dans l'état demandé (reconfigurer coûte une session GPU).
 */
class IseeSteeringOrders(
    private val probe: LiveProbe,
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
) {

    /** Bascule du mode Traversée (puce de la carte caméra) : mêmes masques, voix traversée ou retour. */
    fun toggleCrossing() {
        setActive(!probe.state.value.crossingActive)
    }

    /**
     * Ordre « traverser » / « crossing mode on/off » : consommé ici (jamais
     * LLM), confirmé à voix haute par le chemin navigation. Vrai si [text]
     * portait l'ordre.
     */
    suspend fun runCrossing(text: String): Boolean {
        val command = CrossingVoiceCommand.parse(text) ?: return false
        val wantActive = command == CrossingVoiceAction.Activate
        setActive(wantActive)
        speak(CrossingText.mode(wantActive, settings().speechLanguage))
        return true
    }

    /**
     * Ordre vocal de cap (« va à gauche ») : le guidage recalcule le passage
     * vers ce cap, la confirmation part par le même chemin que la navigation.
     * Vrai si [text] portait un ordre (la dictée ne part ni à Maps ni au LLM).
     */
    suspend fun runSteering(text: String): Boolean {
        val cap = SteeringCommand.parse(text) ?: return false
        probe.setSteering(cap)
        val confirm = SteeringText.confirm(cap, settings().guideStraight, settings().speechLanguage)
        update { it.copy(status = confirm) }
        speak(confirm)
        return true
    }

    /** Bascule commune : sans effet si la sonde est déjà dans l'état demandé. */
    private fun setActive(wantActive: Boolean) {
        if (probe.state.value.crossingActive != wantActive) {
            probe.setCrossingActive(settings(), wantActive)
        }
    }
}
