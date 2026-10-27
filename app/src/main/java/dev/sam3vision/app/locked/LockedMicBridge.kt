package dev.sam3vision.app.locked

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.sam3vision.core.speech.LockedMicPhase
import dev.sam3vision.core.speech.LockedMicState
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
    const val ACTION_ARM = "dev.sam3vision.app.locked.ARM"

    /** Un seul geste : fermé → écoute ; en écoute → envoie. */
    const val ACTION_TOGGLE = "dev.sam3vision.app.locked.TOGGLE"

    /** Tout arrêter (notification « Arrêter », app fermée). */
    const val ACTION_STOP = "dev.sam3vision.app.locked.STOP"

    private val _state = MutableStateFlow(LockedMicState())
    val state: StateFlow<LockedMicState> = _state

    /** Vrai quand le service tourne : la tuile peut lui parler sans risquer un refus. */
    val armed: Boolean get() = _state.value.phase != LockedMicPhase.OFF

    /** Intention vers le service, action comprise. */
    fun intent(context: Context, action: String): Intent =
        Intent(context, LockedMicService::class.java).setAction(action)

    /**
     * Arme le service. À n'appeler que depuis le premier plan (`MainActivity.onResume`) :
     * ailleurs, Android lève `ForegroundServiceStartNotAllowedException`.
     */
    fun arm(context: Context) {
        ContextCompat.startForegroundService(context, intent(context, ACTION_ARM))
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