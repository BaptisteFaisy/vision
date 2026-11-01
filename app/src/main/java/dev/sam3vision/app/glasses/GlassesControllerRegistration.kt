package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState

/**
 * Enregistrement auprès de l'app Meta AI, sorti du contrôleur (garde 500
 * lignes) : demande et relecture, comportement inchangé.
 */

/** Enregistre l'app auprès de l'app Meta AI : la confirmation se fait côté Meta AI. */
fun GlassesController.register() {
    initialize()
    val host = activity ?: return note(GlassesMessages.noActivity())
    // L'app Meta AI peut être absente ou refuser l'ouverture : le SDK lève, il ne rend
    // pas de résultat (`startRegistration` est `void` en 1.0.1) — on ne laisse pas
    // l'exception emporter le fil principal, et l'écran dit ce qui s'est passé.
    runCatching { Wearables.startRegistration(host) }
        .onSuccess {
            // Déjà enregistré (bouton « Re-register ») : pas de demande à
            // confirmer, l'écran le dit au lieu d'afficher un bandeau périmé.
            val already = runCatching {
                Wearables.registrationState.value == RegistrationState.REGISTERED
            }.getOrDefault(false)
            note(if (already) GlassesMessages.registrationConfirmed() else GlassesMessages.registrationStarted())
        }
        .onFailure { error ->
            note(GlassesMessages.registrationFailed(error.message ?: "app Meta AI injoignable"))
        }
}

/**
 * Relit l'enregistrement connu : au retour de l'app Meta AI, le flux du SDK
 * ne réémet pas toujours (état inchangé, processus en cache) et l'écran
 * resterait figé. Appelé au premier plan et par le bouton « Refresh ».
 */
fun GlassesController.refresh() {
    initialize()
    runCatching { Wearables.registrationState.value }
        .onSuccess { state -> onRegistrationState(state == RegistrationState.REGISTERED) }
}
