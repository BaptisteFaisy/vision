package dev.sam3vision.app.glasses.bench

import android.os.SystemClock
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.RegistrationState
import dev.sam3vision.app.glasses.LINK_WAIT_MS
import dev.sam3vision.app.glasses.awaitLinkedDevice

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
            onLog("registration error: ${describe(error)}")
        }
    }
}

private suspend fun GlassesRuntime.onRegistrationState(state: RegistrationState) {
    val decision = registrationDecision(state)
    onLog(decision.message)
    if (runsOffEmitter(decision.step)) {
        // L'étape part après `emit` : le SDK a relâché son verrou d'enregistrement
        // avant que l'appel bloquant ne le reprenne (ANR du 27/10/2026).
        launchSafe { executeRegistrationStep(decision.step) }
    } else {
        executeRegistrationStep(decision.step)
    }
}

private suspend fun GlassesRuntime.executeRegistrationStep(step: RegistrationStep) {
    when (step) {
        RegistrationStep.REQUEST_REGISTRATION -> requestRegistration()
        RegistrationStep.OPEN_SESSION -> openSession()
        RegistrationStep.LOG_ONLY -> Unit
    }
}

/**
 * Un seul lancement par cycle : `startRegistration` ouvre une UI, la relancer à
 * chaque émission de l'état serait agressif.
 *
 * L'appel SDK est synchrone et bloque (clés, négociation) : il part sur IO,
 * jamais sur le main thread — le seul appel DAT du cycle qui ne le faisait pas.
 */
private suspend fun GlassesRuntime.requestRegistration() {
    if (!registrationLatch.arm()) return
    val activity = activity.get()
    if (activity == null) {
        onLog("activity unavailable: registration impossible")
        return
    }
    onLog("opening glasses registration")
    // L'app du compagnon peut être absente : on log sans emporter le collecteur.
    runCatching { io { Wearables.startRegistration(activity) } }
        .onFailure { error -> onLog("registration impossible: ${error.message}") }
}

private suspend fun GlassesRuntime.openSession() {
    if (!sessionLatch.arm()) return
    // `createSession` refuse un appareil qui n'est pas encore `CONNECTED` et rend
    // « Aucun appareil éligible disponible » : mesuré sur les Ray-Ban Meta réelles,
    // le lien met 10 à 30 s à monter après l'enregistrement (le temps de portage).
    // Sans cette attente il fallait arrêter puis relancer la session à la main.
    // La porte elle-même est partagée avec l'app (`GlassesLinkGate.kt`).
    if (!awaitLinkedDevice(SystemClock::elapsedRealtime)) {
        onLog("no linked glasses after ${LINK_WAIT_MS / 1_000} s: wear them, unfolded, then restart")
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

/** Réarme la demande : un prochain état `REGISTERED` retentera la création. */
private fun GlassesRuntime.onSessionFailure(error: DatError) {
    sessionLatch.disarm()
    onLog("session impossible : ${describe(error)}")
}