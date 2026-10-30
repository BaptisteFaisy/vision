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
 * ordre vocal « traverser », ordre de cap (« va à gauche ») et le guidage
 * automatique que le pilote de navigation arme et coupe. Extrait d'
 * [IseeViewModel] (limite 500 lignes) : la logique ne change pas, elle vit
 * simplement à côté — même motif que `ProbeWiring`, `IseeVoiceNav` et sœurs.
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

    /**
     * Mode armé par la vision (passage détecté) : lui seul retombe tout seul
     * quand le passage disparaît. Un armement manuel ou GPS reste jusqu'à sa
     * propre coupe (un passage sans bandes se traverse sans zébrures).
     */
    internal var visionArmed: Boolean = false
        private set

    /** Bascule du mode Traversée (puce de la carte caméra) : mêmes masques, voix traversée ou retour. */
    fun toggleCrossing() {
        visionArmed = false
        setActive(!probe.state.value.crossingActive)
    }

    /**
     * Guidage auto : le rappel proche d'une traversée arme le mode, le
     * passage le coupe (la voix du mode parle, pas d'annonce ici). Sans
     * effet si le mode est déjà dans l'état demandé. [vision] vrai =
     * armement par détection du passage : lui seul retombe tout seul quand
     * le passage disparaît ([visionArmed]).
     */
    fun setAutoCrossing(wantActive: Boolean, vision: Boolean = false) {
        val was = probe.state.value.crossingActive
        setActive(wantActive, auto = true)
        if (!wantActive) visionArmed = false
        else if (vision && !was) visionArmed = true
    }

    /**
     * Ordre « traverser » : consommé ici (jamais LLM), confirmé à voix
     * haute par le chemin navigation. Vrai si [text] portait l'ordre.
     */
    suspend fun runCrossing(text: String): Boolean {
        val command = CrossingVoiceCommand.parse(text) ?: return false
        val wantActive = command == CrossingVoiceAction.Activate
        visionArmed = false
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
    private fun setActive(wantActive: Boolean, auto: Boolean = false) {
        if (probe.state.value.crossingActive != wantActive) {
            probe.setCrossingActive(settings(), wantActive, auto)
        }
    }
}
