package dev.sam3vision.app.voicenav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.core.nav.GuidanceUpdate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * API ViewModel de la navigation vocale, pour l'intégrateur.
 *
 * Enveloppe fine de [VoiceNavSession] (toute la logique y vit, testée en
 * JVM) : les écrans observent [state], les boutons appellent les méthodes —
 * chacune lance son travail dans [viewModelScope], l'appelant Compose ne
 * gère aucune coroutine.
 *
 * Construction (pas de `viewModel()` sans fabrique : la session porte le
 * lanceur Android et les voix, construits par l'intégrateur) :
 *
 * ```kotlin
 * val session = remember {
 *     VoiceNavSession(launcher, { settings.navigationGuidanceEnabled },
 *         announcer, voiceNavAnnouncer, routeLoader = monChargeur)
 * }
 * val nav = remember { VoiceNavViewModel(session) }
 * ```
 *
 * Entrée vocale : le transcript dicté arrive par
 * `session.onVoiceTranscript(texte)` — `suspend`, à appeler depuis le
 * pipeline de dictée **avant** le chemin question → LLM (même place que
 * `NavigationHook.onTranscript`) ; `true` = consommé, ne pas poursuivre.
 * Mises à jour du moteur : [onGuidanceUpdate].
 */
class VoiceNavViewModel(private val session: VoiceNavSession) : ViewModel() {

    /** État courant des écrans (repos, confirmation, guidage). */
    val state: StateFlow<VoiceNavUiState> = session.state

    /** La session sous-jacente : entrée vocale `suspend` depuis la dictée. */
    fun session(): VoiceNavSession = session

    /** Bouton « Démarrer » de l'écran de confirmation. */
    fun confirmDestination() {
        viewModelScope.launch { session.confirmDestination() }
    }

    /** Bouton « Annuler » de l'écran de confirmation. */
    fun cancelConfirmation() {
        session.cancelConfirmation()
    }

    /** Bouton « Arrêter » de l'écran de guidage. */
    fun stopGuidance() {
        viewModelScope.launch { session.stopGuidance() }
    }

    /** L'intégrateur pousse ici l'état du moteur d'itinéraire. */
    fun onGuidanceUpdate(update: GuidanceUpdate) {
        viewModelScope.launch { session.onGuidanceUpdate(update) }
    }
}
