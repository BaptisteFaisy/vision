package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.voicenav.NavClarify
import dev.sam3vision.core.voicenav.NavClarifyText

/**
 * Le filet de la dictée navigation : un ordre sans destination comprise
 * (« je veux aller », « emmène-moi »…) demande la destination au lieu de
 * partir au LLM décrire la scène. Appelé dans la dictée après les ordres
 * de mouvement, la navigation in-app, le handoff Maps et la traversée —
 * juste avant le chemin question → Room → LLM.
 *
 * Consommé = `true` (statut affiché + phrase dite par le chemin
 * navigation) ; sinon `false` et la dictée poursuit son chemin normal.
 */
class IseeNavClarify(
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
) {

    /** Vrai si [text] était un ordre de navigation incompris (consommé). */
    suspend fun run(text: String): Boolean {
        if (!runCatching { NavClarify.isUnclearOrder(text) }.getOrDefault(false)) return false
        val phrase = NavClarifyText.ask(settings().speechLanguage)
        update { it.copy(status = phrase) }
        speak(phrase)
        return true
    }
}
