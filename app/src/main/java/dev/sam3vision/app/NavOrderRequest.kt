package dev.sam3vision.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Destination dictée sur le **direct** (bouton « Parler »), posée à
 * l'intention de la navigation de l'accueil : le direct n'a pas de session de
 * navigation, et sans ce passage une phrase comme « aller gare Montparnasse »
 * partait au LLM, qui décrivait la pièce filmée.
 *
 * Le texte est transmis **brut** (pas la destination extraite) : la session le
 * repasse dans ses propres parseurs, comme une dictée de l'accueil.
 * [AppNav] consomme la demande et ouvre l'écran de navigation ; usage unique,
 * une rotation ne rejoue pas la destination.
 */
object NavOrderRequest {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** Pose [transcript] pour l'accueil (écrase la demande précédente). */
    fun push(transcript: String) {
        _pending.value = transcript
    }

    /** Demande en attente, `null` si aucune (lecture unique). */
    fun consume(): String? = _pending.value.also { _pending.value = null }
}
