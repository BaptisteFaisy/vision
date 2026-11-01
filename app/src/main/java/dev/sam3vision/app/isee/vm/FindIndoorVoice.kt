package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.FindIndoorClips
import dev.sam3vision.core.picto.FindIndoorCommand
import dev.sam3vision.core.picto.FindIndoorLevels
import dev.sam3vision.core.picto.FindIndoorOrder
import dev.sam3vision.core.picto.PictoPlaceMode

/**
 * Ordres vocaux « find my X » / « find off » (spec v4).
 *
 * Essaie avant le picto historique ([IseePictoVoice]) : dedans, un ordre
 * valide démarre la chasse ([onFind] : X rejoint les masques SAM, son
 * picto se crée à sa première détection) ; dehors, il est refusé avec un
 * message parlé, jamais de picto dehors. « find off » efface la chasse
 * pendante ([onFindOff]), dedans comme dehors. Un « find » d'objet hors
 * des 10 est consommé avec une aide courte (le détail va au statut),
 * jamais une question LLM ; ce qui n'est pas un ordre rend `false`.
 *
 * Tout part en clips anglais ([post], signature de VoiceBus : niveau,
 * texte, clip) — zéro appel réseau. Le statut visuel reprend le texte
 * parlé. [post] est injecté pour les tests ; en production le câblage le
 * branche sur VoiceBus (snippet au parent, IseeViewModel interdit).
 */
class FindIndoorVoice(
    private val update: StateUpdate,
    private val onFind: (String) -> Unit = {},
    private val onFindOff: () -> Unit = {},
    private val placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEDANS },
    private val post: (level: Int, text: String, clipId: String?) -> Unit = { _, _, _ -> },
) {

    /**
     * Rend `true` quand [text] portait un ordre find (texte consommé).
     * L'écran affiche le texte parlé.
     */
    suspend fun run(text: String): Boolean {
        when (val order = FindIndoorOrder.parse(text)) {
            is FindIndoorCommand.Off -> return off()
            is FindIndoorCommand.Start -> return start(order)
            null -> Unit
        }
        if (FindIndoorOrder.isUnknownFind(text)) return unknown()
        return false
    }

    /** « find off » : chasse effacée, confirmé en clip, dedans ou dehors. */
    private fun off(): Boolean {
        onFindOff()
        emit(FindIndoorClips.OFF.id, FindIndoorClips.OFF.text)
        return true
    }

    /**
     * « find my X » : dedans la chasse démarre, dehors elle est refusée
     * avec un message parlé (jamais de picto dehors).
     */
    private fun start(order: FindIndoorCommand.Start): Boolean {
        if (!placeMode().pictoEnabled) {
            emit(FindIndoorClips.REFUSED.id, FindIndoorClips.REFUSED.text)
            return true
        }
        onFind(order.target.concept)
        val clip = FindIndoorClips.started(order.target)
        emit(clip.id, clip.text)
        return true
    }

    /** « find » hors des 10 : aide courte parlée, détail au statut. */
    private fun unknown(): Boolean {
        post(FindIndoorLevels.FIND, FindIndoorClips.UNKNOWN.text, FindIndoorClips.UNKNOWN.id)
        update { it.copy(status = FindIndoorClips.SUPPORTED) }
        return true
    }

    /** Un clip parlé ([post]) et affiché (statut), au cran find. */
    private fun emit(clipId: String, text: String) {
        post(FindIndoorLevels.FIND, text, clipId)
        update { it.copy(status = text) }
    }
}
