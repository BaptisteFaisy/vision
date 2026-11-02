package dev.sam3vision.app.glasses

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Journal de la chaîne lunettes (recette : `logcat -s GlassesChain`). */
private const val TAG = "GlassesChain"

/**
 * Ouverture de session, sortie du contrôleur (garde 500 lignes) : attente du
 * lien, création, un seul rejou quand le SDK semble transitoirement pas prêt
 * ([SessionRetry]), et essais espacés quand la session créée meurt aussitôt
 * ([settled]).
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
    /** Nouvel essai : la phase repasse en démarrage, côté contrôleur. */
    private val starting: () -> Unit,
) {
    private var openJob: Job? = null
    private var retryJob: Job? = null

    /** Quand la session en cours a été créée (horloge [clock]) : dit la mort rapide. */
    private var startMs: Long = 0L

    /** Morts rapides déjà rejouées dans ce cycle (remis à zéro par [open]). */
    private var quickDeaths: Int = 0

    /** Ouvre une session ; tue une ouverture précédente restée en vie. */
    fun open() {
        quickDeaths = 0
        relaunch()
    }

    /** Relance l'ouverture sans toucher au compteur (nouvel essai automatique). */
    private fun relaunch() {
        openJob?.cancel()
        retryJob?.cancel()
        retryJob = null
        openJob = scope.launch { openSession() }
    }

    /** Arrêt demandé : l'ouverture en cours n'aboutira pas. */
    fun cancel() {
        openJob?.cancel()
        openJob = null
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Session retombée (`STOPPED`) : les capacités se libèrent toujours, puis
     * une mort rapide se rejoue seule, espacée ([quickDeathDelayMs]) — passé
     * [QUICK_DEATH_MAX_RETRIES], c'est un refus et il se dit comme tel.
     */
    fun settled(release: () -> Unit) {
        release()
        if (!isQuickDeath(startMs, clock())) {
            quickDeaths = 0
            return
        }
        if (quickDeaths >= QUICK_DEATH_MAX_RETRIES) {
            quickDeaths = 0
            note(GlassesMessages.sessionRefusedByGlasses())
            return
        }
        quickDeaths += 1
        note(GlassesMessages.sessionRetrying(quickDeaths, QUICK_DEATH_MAX_RETRIES))
        retryJob?.cancel()
        val waitMs = quickDeathDelayMs(quickDeaths)
        retryJob = scope.launch {
            delay(waitMs)
            retryJob = null
            // L'utilisateur a repris la main entre-temps : son ordre gagne.
            if (snapshot().phase != SessionPhase.STOPPED) return@launch
            starting()
            relaunch()
        }
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
                startMs = clock()
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
