package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.HomePresetRequest
import dev.sam3vision.app.RouteRequest
import dev.sam3vision.core.live.MaisonEnterCommand
import dev.sam3vision.core.live.MaisonEnterText
import dev.sam3vision.core.settings.Settings

/**
 * Ordre vocal « maison » / « house » : ouvre le direct sur les essentiels
 * (comme le bouton Maison) et le confirme à voix haute. Appelé sur le texte
 * dicté en premier, avant la navigation : le mot isolé est un ordre de mode,
 * jamais une destination ni une question LLM.
 *
 * [openLiveHome] fait comme le bouton Maison (preset essentiels + écran
 * direct) ; injecté pour les tests, le défaut parle aux vrais singletons.
 */
class MaisonEnterOrder(
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
    private val openLiveHome: () -> Unit = { HomePresetRequest.pushDefault(); RouteRequest.push("live") },
) {

    /**
     * Rend `true` quand [text] ordonnait d'entrer (texte consommé).
     * L'écran affiche la confirmation, dite par le chemin navigation.
     */
    suspend fun run(text: String): Boolean {
        if (!MaisonEnterCommand.isEnterOrder(text)) return false
        openLiveHome()
        val confirm = MaisonEnterText.entered(settings().speechLanguage)
        update { it.copy(status = confirm) }
        speak(confirm)
        return true
    }
}
