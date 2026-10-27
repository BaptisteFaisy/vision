package dev.sam3vision.app.glasses.bench

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference

/**
 * Cycle de vie DAT du spike lunettes : enregistrement → session → caméra → flux
 * de frames. Cette classe ne porte que le démarrage et l'arrêt ; le travail est
 * réparti en unités nommées :
 *
 * - [GlassesRegistrationPhase] : enregistrement et ouverture de session ;
 * - [GlassesDeviceSession] : session d'appareil, caméra, flux ;
 * - [GlassesFramePump] : collecte et étranglement des frames ;
 * - [GlassesSessionPolicy] : les décisions (état, erreur, attente), testées sur JVM.
 *
 * `Wearables.initialize` est déjà fait dans [GlassesApp] : on n'y touche pas.
 * La machine à états est pilotée par `Wearables.registrationState` (source de
 * vérité du SDK) : enregistrement si nécessaire, puis session, puis caméra
 * seulement quand la session atteint `STARTED`, puis flux vidéo étranglé à une
 * frame toutes ~1,5 s vers [onFrame].
 *
 * Les coroutines internes vivent dans un `SupervisorJob` enfant du job de
 * [scope] : [stop] les annule sans jamais annuler le scope de l'appelant.
 * Tout ce qui peut bloquer (création de session, handshake caméra, encodage
 * JPEG) est basculé sur `Dispatchers.IO`, donc un [scope] sur le main thread
 * convient. En contrepartie [onFrame] s'exécute sur le dispatcher de [scope] :
 * il est `suspend` et doit basculer son réseau sur IO lui-même.
 *
 * [onLog] est appelé depuis plusieurs threads (collecteurs DAT + nettoyage) :
 * à l'appelant de revenir au main s'il affiche l'état.
 */
class GlassesSession(
    context: Context,
    private val scope: CoroutineScope,
    private val onFrame: suspend (JpegFrame) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val appContext: Context = context.applicationContext

    /**
     * Cycle courant, `null` quand rien ne tourne. Volatile parce que [stop] peut
     * être appelé du main thread pendant qu'un collecteur tourne ailleurs.
     */
    @Volatile
    private var runtime: GlassesRuntime? = null

    /** Démarre enregistrement + session + caméra. Sans effet si déjà démarré. */
    fun start(activity: Activity) {
        if (runtime != null) {
            onLog("cycle lunettes déjà démarré")
            return
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        val rt = GlassesRuntime(
            appContext = appContext,
            job = job,
            scope = CoroutineScope(scope.coroutineContext + job),
            activity = WeakReference(activity),
            encoder = GlassesFrameEncoder(),
            onFrame = onFrame,
            onLog = onLog,
            requestStop = ::stop,
        )
        runtime = rt
        onLog("démarrage du cycle lunettes")
        rt.observeRegistration()
    }

    /** Libère flux, caméra puis session. Idempotent : un second appel ne fait rien. */
    fun stop() {
        val rt = runtime ?: return
        runtime = null
        onLog("arrêt du cycle lunettes")
        // On coupe d'abord les collecteurs : plus aucune frame ne part pendant
        // la libération des ressources DAT.
        rt.job.cancel()
        rt.frames.close()
        rt.release()
    }
}