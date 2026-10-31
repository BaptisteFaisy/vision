package dev.sam3vision.app.live

import dev.sam3vision.app.NavOrderRequest
import dev.sam3vision.core.crossing.CrossingVoiceCommand
import dev.sam3vision.core.live.SteeringCommand
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.voicenav.NavClarify
import dev.sam3vision.core.voicenav.NavClarifyText
import dev.sam3vision.core.voicenav.VoiceDestinationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ordres de navigation dictés sur le direct, essayés **avant** les ordres de
 * mode et le LLM : « je veux aller à X », « aller X », « direction X »…
 *
 * - destination comprise → [NavOrderRequest] (l'accueil ouvre l'écran de
 *   navigation, comme pour une dictée de l'accueil) ;
 * - ordre traversée (« traverser », « crossing mode on/off ») → [NavOrderRequest]
 *   aussi (l'accueil l'active, le direct n'a pas de sonde) ;
 * - ordre de navigation **sans** destination comprise → la phrase redemande
 *   l'adresse ([NavClarifyText]) au lieu de laisser la question partir au
 *   LLM, qui répondait par une description de la pièce.
 *
 * Aucune condition d'URL Valhalla : sans elle, la session ouvre quand même
 * l'écran de confirmation et « Démarrer » bascule sur Google Maps piéton.
 *
 * Pur côté décision ([VoiceDestinationParser], [NavClarify]) : le seul
 * effet de bord est la demande posée ou la phrase dite.
 */
object LiveNavOrders {

    /**
     * Vrai si [question] était un ordre de navigation, traité ici (rien
     * d'autre à faire) ; faux si la question suit son chemin habituel.
     */
    fun handle(
        question: String,
        settings: Settings,
        scope: CoroutineScope,
        speak: suspend (Settings, String) -> Unit,
    ): Boolean {
        // « va à gauche », « continue tout droit » : un cap, pas une destination.
        if (SteeringCommand.parse(question) != null) return false
        // « traverser », « crossing mode on/off » : l'accueil active le mode
        // (le direct n'a pas de sonde) ; après le cap, comme sur l'accueil.
        if (CrossingVoiceCommand.parse(question) != null) {
            NavOrderRequest.push(question)
            return true
        }
        if (VoiceDestinationParser.parse(question) != null) {
            NavOrderRequest.push(question)
            return true
        }
        if (!runCatching { NavClarify.isUnclearOrder(question) }.getOrDefault(false)) return false
        scope.launch { speak(settings, NavClarifyText.ask(settings.speechLanguage)) }
        return true
    }
}
