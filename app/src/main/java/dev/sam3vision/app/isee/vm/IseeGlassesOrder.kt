package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.glasses.GlassesMessages
import dev.sam3vision.app.glasses.GlassesSessionControl
import dev.sam3vision.app.glasses.SessionToggle
import dev.sam3vision.core.speech.GlassesSessionOrder

/**
 * Ordre vocal « iSee » : le mot seul bascule la session des lunettes —
 * ouverte si une paire est liée en Bluetooth, arrêtée si elle tourne.
 * Appelé sur le texte dicté (micro du téléphone comme des lunettes) avant
 * le LLM : une bascule ne fait jamais d'appel réseau.
 *
 * [control] est câblé par `AppNav` ; non câblé, l'ordre ne consomme rien et
 * le texte poursuit son chemin comme avant.
 */
class IseeGlassesOrder(
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
    private val control: () -> GlassesSessionControl?,
) {

    /** Rend `true` quand [text] était l'ordre (texte consommé, confirmation dite). */
    suspend fun run(text: String): Boolean {
        if (!GlassesSessionOrder.isSessionOrder(text)) return false
        val action = control()?.toggle() ?: return false
        val confirm = when (action) {
            SessionToggle.START -> GlassesMessages.toggleStarted()
            SessionToggle.STOP -> GlassesMessages.toggleStopped()
            SessionToggle.NOT_LINKED -> GlassesMessages.toggleNotLinked()
        }.text
        update { it.copy(status = confirm) }
        speak(confirm)
        return true
    }
}
