package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.speech.DictationOrder
import dev.sam3vision.core.speech.DictationOrders
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Les 10 effets des ordres de dictée (spec v4), exécutés par le câblage
 * ([IseeViewModel]) : le routeur ne fait que décider, chaque effet parle
 * aux collaborateurs existants (modes, traversée, picto, GPS, lunettes).
 * Un effet confirme à voix haute par le chemin navigation, comme les
 * ordres qu'il remplace.
 */
interface DictationActions {
    /** « mode outdoor » : passer en mode dehors. */
    suspend fun onOutdoor()

    /** « mode indoor » : passer en mode dedans. */
    suspend fun onIndoor()

    /** « mode crossing on/off » : activer ou couper le mode Traversée. */
    suspend fun onCrossing(active: Boolean)

    /** « mode find my X » : chercher l'objet [label], quel qu'il soit. */
    suspend fun onFind(label: String)

    /** « mode find off » : arrêter la recherche d'objet en cours. */
    suspend fun onFindOff()

    /** « mode GPS I want to go to X » : guider vers [destination]. */
    suspend fun onGpsGo(destination: String)

    /** « mode GPS off » : couper le guidage GPS. */
    suspend fun onGpsOff()

    /** « mode glasses on/off » : ouvrir ou fermer la session lunettes. */
    suspend fun onGlasses(on: Boolean)
}

/**
 * Routeur de dictée iSee (spec v4 : ordres « mode … » uniquement) : « mode … »
 * en tête de phrase ⇒ l'un des 10 ordres ([DictationActions]) ; tout le
 * reste ⇒ `false`, l'appelant envoie le texte au LLM comme question. Sans
 * état et sans condition de mode : la dictée reste active partout, traversée
 * comprise — un ordre dit en traversée est routé comme ailleurs.
 *
 * Pur côté décision ([DictationOrders.parse]) : le seul effet de bord est
 * l'appel de l'effet correspondant, une fois.
 */
class IseeDictationRouter(
    private val actions: DictationActions,
) {

    /**
     * Vrai si [text] était un ordre (exécuté, consommé) ; faux si c'est une
     * question pour le LLM (rien n'est appelé).
     */
    suspend fun run(text: String): Boolean {
        return when (val order = DictationOrders.parse(text)) {
            null -> false
            DictationOrder.Outdoor -> { actions.onOutdoor(); true }
            DictationOrder.Indoor -> { actions.onIndoor(); true }
            DictationOrder.CrossingOn -> { actions.onCrossing(true); true }
            DictationOrder.CrossingOff -> { actions.onCrossing(false); true }
            is DictationOrder.Find -> { actions.onFind(order.label); true }
            DictationOrder.FindOff -> { actions.onFindOff(); true }
            is DictationOrder.GpsGo -> { actions.onGpsGo(order.destination); true }
            DictationOrder.GpsOff -> { actions.onGpsOff(); true }
            DictationOrder.GlassesOn -> { actions.onGlasses(true); true }
            DictationOrder.GlassesOff -> { actions.onGlasses(false); true }
        }
    }
}

/**
 * Confirmations parlées propres au routeur, dans la langue de la voix :
 * seul l'arrêt de recherche n'a pas de phrase existante à réutiliser —
 * tous les autres ordres rejouent les confirmations de leurs ordres
 * d'origine. Pures et testées en JVM.
 */
object DictationRouterText {

    /** La recherche d'objet est arrêtée (« mode find off »). */
    fun findStopped(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Recherche arrêtée."
        SpeechLanguage.En -> "Search stopped."
    }
}
