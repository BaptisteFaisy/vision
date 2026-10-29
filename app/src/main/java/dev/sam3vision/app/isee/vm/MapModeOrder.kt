package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.nav.MapModeCommand
import dev.sam3vision.core.nav.MapModeText
import dev.sam3vision.core.settings.Settings

/**
 * Ordre vocal du **mode navigation** : dire « navigation », « carte », « plan »,
 * « mode navigation », « ouvre la carte » ou « où suis-je » ouvre l'écran carte
 * — position GPS à l'écran, sans destination — et le confirme à voix haute.
 * « quitte la carte » / « arrête la navigation » le referme.
 *
 * Appelé sur le texte dicté **avant** la navigation et avant le chemin
 * question → LLM (même place que [MaisonEnterOrder]) : une commande ne fait
 * aucun appel réseau et ne vole ni destination ni question.
 *
 * Ouverture refusée (un guidage, une confirmation ou une arrivée est déjà à
 * l'écran) : l'ordre est consommé **sans** annonce — on ne dit pas « la carte
 * est à l'écran » quand c'est le guidage qui l'est.
 */
class MapModeOrder(
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
    private val enterMap: () -> Boolean,
    private val leaveMap: () -> Boolean,
) {

    /**
     * Rend `true` quand [text] portait un ordre du mode (texte consommé) :
     * ouverture, fermeture, ou ouverture refusée.
     */
    suspend fun run(text: String): Boolean {
        if (MapModeCommand.isLeaveOrder(text) && leaveMap()) {
            say(MapModeText.left(settings().speechLanguage))
            return true
        }
        if (!MapModeCommand.isEnterOrder(text)) return false
        if (enterMap()) say(MapModeText.entered(settings().speechLanguage))
        return true
    }

    /** Confirmation parlée par le chemin navigation (même audio que les bulles). */
    private suspend fun say(text: String) {
        update { it.copy(status = text) }
        speak(text)
    }
}

/**
 * Fabrique pour iSee : la carte est celle de la session de navigation vocale
 * ([IseeVoiceNav]) — l'ouverture est refusée si un guidage occupe déjà l'écran.
 */
fun mapModeOrderOf(
    settings: () -> Settings,
    speak: suspend (String) -> Unit,
    update: StateUpdate,
    voiceNav: IseeVoiceNav,
): MapModeOrder = MapModeOrder(
    settings = settings,
    speak = speak,
    update = update,
    enterMap = voiceNav::enterMap,
    leaveMap = voiceNav::leaveMap,
)
