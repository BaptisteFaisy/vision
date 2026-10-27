package dev.sam3vision.app.glasses.bench

import android.os.SystemClock
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.delay

/**
 * Phase d'enregistrement : observer l'état DAT, demander l'enregistrement quand
 * c'est possible, ouvrir la session une fois `REGISTERED`.
 *
 * Aucune décision ici : `registrationDecision` (GlassesSessionPolicy) dit
 * l'étape et la ligne de journal ; ces fonctions ne font que l'exécuter.
 */
internal fun GlassesRuntime.observeRegistration() {
    launchSafe {
        Wearables.registrationState.collect { state -> onRegistrationState(state) }
    }
    // Flux d'erreurs séparé : il donne le motif exact d'un échec, que le seul
    // enchaînement d'états ne révèle pas.
    launchSafe {
        Wearables.registrationErrorStream.collect { error ->
            onLog("erreur d'enregistrement : ${describe(error)}")
        }
    }
}

private suspend fun GlassesRuntime.onRegistrationState(state: RegistrationState) {
    val decision = registrationDecision(state)
    onLog(decision.message)
    when (decision.step) {
        RegistrationStep.REQUEST_REGISTRATION -> requestRegistration()
        RegistrationStep.OPEN_SESSION -> openSession()
        RegistrationStep.LOG_ONLY -> Unit
    }
}

/**
 * Un seul lancement par cycle : `startRegistration` ouvre une UI, la relancer à
 * chaque émission de l'état serait agressif.
 */
private fun GlassesRuntime.requestRegistration() {
    if (!registrationLatch.arm()) return
    val activity = activity.get()
    if (activity == null) {
        onLog("activity indisponible : enregistrement impossible")
        return
    }
    onLog("ouverture de l'enregistrement des lunettes")
    // L'app du compagnon peut être absente : on log sans emporter le collecteur.
    runCatching { Wearables.startRegistration(activity) }
        .onFailure { error -> onLog("enregistrement impossible : ${error.message}") }
}

private suspend fun GlassesRuntime.openSession() {
    if (!sessionLatch.arm()) return
    // `createSession` refuse un appareil qui n'est pas encore `CONNECTED` et rend
    // « Aucun appareil éligible disponible » : mesuré sur les Ray-Ban Meta réelles,
    // le lien met 10 à 30 s à monter après l'enregistrement (le temps de portage).
    // Sans cette attente il fallait arrêter puis relancer la session à la main.
    if (!awaitLinkedDevice()) {
        onLog("aucune lunette liée après ${LINK_WAIT_MS / 1_000} s : portez-les, dépliées, puis relancez")
        sessionLatch.disarm()
        return
    }
    val result = io { Wearables.createSession(AutoDeviceSelector()) }
    // `fold` du SDK prend des callbacks non suspend : le travail suspendu est
    // relancé sur le scope interne.
    result.fold(
        onSuccess = { session -> launchSafe { runSession(session) } },
        onFailure = { error, _ -> onSessionFailure(error) },
    )
}

/**
 * Attend qu'une paire soit réellement `CONNECTED` (borne [LINK_WAIT_MS]).
 * Rend `false` au bout du délai : l'appelant le dit et n'ouvre pas de session morte.
 */
private suspend fun GlassesRuntime.awaitLinkedDevice(): Boolean {
    val deadline = SystemClock.elapsedRealtime() + LINK_WAIT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        val links = runCatching { linkedStates() }.getOrDefault(emptyList())
        if (hasLinkedDevice(links)) return true
        delay(LINK_POLL_MS)
    }
    return false
}

/** États de lien des paires connues du SDK ; vide tant qu'il n'y en a aucune. */
private fun linkedStates(): List<LinkState> = Wearables.devices.value.mapNotNull { id ->
    Wearables.devicesMetadata[id]?.value?.linkState
}

/** Réarme la demande : un prochain état `REGISTERED` retentera la création. */
private fun GlassesRuntime.onSessionFailure(error: DatError) {
    sessionLatch.disarm()
    onLog("session impossible : ${describe(error)}")
}