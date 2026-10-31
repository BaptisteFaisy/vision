package dev.sam3vision.app.voicenav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Destination GPS en cours, **en mémoire tant que le guidage est actif** :
 * posée à chaque entrée en [VoiceNavUiState.Guiding], effacée à chaque
 * sortie (arrêt, arrivée, annulation, « désactive le GPS », fiche lieu) —
 * sans discontinuité, une nouvelle destination écrase la précédente.
 *
 * Les écrans ne partagent pas la session (`IseeVoiceNav` vit dans
 * `IseeViewModel`, le direct n'en a pas) : cet objet est le point unique
 * que les trois chemins question → LLM consultent (`IseeAnswers`, vécu
 * iSee, `LiveAskSession`, vécu direct, et `LockedMicAnswer`, micro
 * verrouillé).
 */
object GpsDestinationMemory {

    private val _destination = MutableStateFlow<String?>(null)

    /** Destination guidée, `null` hors guidage (repos, confirmation, arrivée). */
    val destination: StateFlow<String?> = _destination

    /**
     * Mémorise [destination] (`null`/blanc = oubli : le rappel de la
     * session passe `null` hors guidage, jamais une chaîne vide).
     */
    fun remember(destination: String?) {
        _destination.value = destination?.takeIf { it.isNotBlank() }
    }

    /** Oubli explicite (tests, fin de séance). */
    fun clear() {
        _destination.value = null
    }
}
