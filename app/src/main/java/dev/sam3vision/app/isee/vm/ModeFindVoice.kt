package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.picto.PictoVoiceText
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.DictationOrder
import dev.sam3vision.core.speech.DictationOrders

/**
 * Ordre vocal « mode find my X » : l'unique ordre de recherche et de
 * création picto, quel que soit l'objet. Dedans, la chasse démarre ([onFind] :
 * X rejoint les masques SAM, son pictogramme se crée à sa première détection
 * puis s'ancre sur lui) ; dehors, il est refusé avec un message parlé,
 * jamais de picto dehors. Tout le reste (ordres sans « mode », questions)
 * rend `false` et poursuit son chemin vers le LLM.
 *
 * Essaie avant le lieu ([PlaceModeOrder]) dans [IseeModeOrders] : « mode
 * find my indoor plant » nomme une plante, pas le mode dedans. Les
 * confirmations parlent par le chemin navigation ([speak], synthèse vocale)
 * : aucun clip pré-enregistré ne peut couvrir un objet quelconque.
 */
class ModeFindVoice(
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
    private val onFind: (String) -> Unit = {},
    private val placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEDANS },
) {

    /**
     * Rend `true` quand [text] portait l'ordre (texte consommé).
     * L'écran affiche la confirmation parlée.
     */
    suspend fun run(text: String): Boolean {
        val order = DictationOrders.parse(text) as? DictationOrder.Find ?: return false
        val language = settings().speechLanguage
        if (!placeMode().pictoEnabled) {
            confirm(PictoVoiceText.refused(language))
            return true
        }
        onFind(order.label)
        confirm(PictoVoiceText.findStarted(order.label, language))
        return true
    }

    /** Confirmation parlée (navigation) et affichée (statut). */
    private suspend fun confirm(line: String) {
        update { it.copy(status = line) }
        speak(line)
    }
}
