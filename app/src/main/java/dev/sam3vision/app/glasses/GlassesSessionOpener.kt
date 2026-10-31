package dev.sam3vision.app.glasses

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Journal de la chaîne lunettes (recette : `logcat -s GlassesChain`). */
private const val TAG = "GlassesChain"

/**
 * Ouverture de session, sortie du contrôleur (garde 500 lignes) : attente du
 * lien, création, et un seul rejou quand le SDK semble transitoirement pas
 * prêt ([SessionRetry]).
 *
 * Une seule ouverture à la fois : `open` tue une ouverture précédente restée
 * en vie, et chaque étape revérifie la phase — un arrêt demandé pendant
 * l'attente ou la création n'ouvre pas de session impromptue, et deux
 * ouvertures ne créent jamais deux sessions (le SDK répondait
 * `UNEXPECTED_ERROR` au doublon).
 */
internal class GlassesSessionOpener(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val snapshot: () -> GlassesUiState,
    /** Session retombée au repos (+ invocation vocale désarmée), côté contrôleur. */
    private val idle: () -> Unit,
    private val note: (GlassesMessage) -> Unit,
    private val report: (DeviceSessionError, SessionPhase, GlassesMessage?) -> Unit,
    private val onCreated: (DeviceSession) -> Unit,
) {
    private var openJob: Job? = null

    /** Ouvre une session ; tue une ouverture précédente restée en vie. */
    fun open() {
        openJob?.cancel()
        openJob = scope.launch { openSession() }
    }

    /** Arrêt demandé : l'ouverture en cours n'aboutira pas. */
    fun cancel() {
        openJob?.cancel()
        openJob = null
    }

    /**
     * `createSession` refuse une paire pas encore `CONNECTED` — le « Aucune paire éligible »
     * affiché sur des lunettes pourtant appairées : on attend le lien ([GlassesLinkGate]).
     */
    private suspend fun openSession(retries: SessionRetry = SessionRetry()) {
        // Arrêt demandé avant même l'attente : rien à ouvrir.
        if (snapshot().phase != SessionPhase.STARTING) return
        // Paire simulée : aucun lien Bluetooth à attendre.
        val linked = snapshot().mockActive || awaitLinkedDevice(clock) {
            note(GlassesMessages.waitingForLink())
        }
        if (!linked) {
            idle()
            note(GlassesMessages.noLinkedDevice())
            return
        }
        // Arrêt demandé pendant l'attente du lien : pas de session impromptue.
        if (snapshot().phase != SessionPhase.STARTING) return
        // `fold` n'est pas suspendu : il décide, et le rejou part après.
        val retry = Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { created ->
                Log.i(TAG, "session créée, démarrage")
                onCreated(created)
                null
            },
            onFailure = { error, _ ->
                // Arrêt demandé pendant la création : l'échec ne se rejoue ni ne se dit.
                if (snapshot().phase != SessionPhase.STARTING) {
                    null
                } else if (retries.shouldRetry(error)) {
                    Log.i(TAG, "session refusée (${error.description}) : nouvel essai")
                    retries.next()
                } else {
                    Log.w(TAG, "session refusée : ${error.description}")
                    idle()
                    report(error, snapshot().phase, snapshot().message)
                    null
                }
            },
        )
        if (retry != null && snapshot().phase == SessionPhase.STARTING) openSession(retry)
    }
}
