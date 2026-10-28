package dev.sam3vision.app.locked

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.sam3vision.core.speech.LockedMic
import dev.sam3vision.core.speech.LockedMicPhase
import dev.sam3vision.core.speech.LockedMicState
import dev.sam3vision.core.speech.MicStartActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Le pont entre les gestes et le service du micro verrouillé.
 *
 * Deux appelants seulement : l'activité, qui **arme** le service tant qu'elle est visible (une
 * app en arrière-plan n'a plus le droit de démarrer un service de premier plan — c'est la seule
 * voie légale), et la tuile des réglages rapides, qui **bascule** le micro depuis l'écran de
 * verrouillage. L'état est publié ici pour que la tuile sache quoi afficher sans parler au
 * service.
 */
object LockedMicBridge {

    /** Armer : service de premier plan, micro fermé, notification prête. */
    const val ACTION_ARM = MicStartActions.ARM

    /** Un seul geste : fermé → écoute ; en écoute → envoie. */
    const val ACTION_TOGGLE = MicStartActions.TOGGLE

    /** Tout arrêter (notification « Arrêter », app fermée). */
    const val ACTION_STOP = MicStartActions.STOP

    private val _state = MutableStateFlow(LockedMicState())
    val state: StateFlow<LockedMicState> = _state

    /** Vrai quand le service tourne : la tuile peut lui parler sans risquer un refus. */
    val armed: Boolean get() = _state.value.phase != LockedMicPhase.OFF

    /** Intention vers le service, action comprise. */
    fun intent(context: Context, action: String): Intent =
        Intent(context, LockedMicService::class.java).setAction(action)

    /**
     * Arme le service. À n'appeler que depuis le premier plan (`MainActivity.onResume`) :
     * ailleurs, Android refuse le démarrage en arrière-plan.
     *
     * Démarrage **simple** (`startService`), pas `startForegroundService` : le service prend
     * lui-même le premier plan en quelques millisecondes (ou s'arrête, arrêt parlé), et sans
     * promesse de premier plan au démarrage, le FATAL `...DidNotStartInTime` est
     * structurellement impossible (2× au relevé du 28/10/2026 sur un démarrage à froid, où la
     * livraison d'`onStartCommand` avait manqué le délai système).
     *
     * Meilleur effort : un appel manqué se rejoue à la prochaine reprise — jamais un FATAL.
     */
    fun arm(context: Context) {
        runCatching { context.startService(intent(context, ACTION_ARM)) }
            .onFailure { Log.w(LockedMic.TAG, "armement manqué : ${it.javaClass.simpleName} — ${it.message}") }
    }

    /** Bascule le micro d'un seul geste ; rend l'échec au lieu de le lever (tuile). */
    fun toggle(context: Context): Boolean = runCatching {
        context.startService(intent(context, ACTION_TOGGLE))
    }.isSuccess

    /** Arrête le service et sa notification. */
    fun disarm(context: Context) {
        runCatching { context.startService(intent(context, ACTION_STOP)) }
    }

    /** Le service publie chaque nouvel état ici (la tuile lit la phase). */
    fun publish(state: LockedMicState) {
        _state.value = state
    }
}