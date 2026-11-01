package dev.sam3vision.app.glasses

import android.util.Log
import com.meta.wearable.dat.motion.Motion
import dev.sam3vision.core.distance.HeadPoseMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Journal de la pose tête (recette : `logcat -s GlassesHeadPose`). */
private const val TAG = "GlassesHeadPose"

/**
 * Pose tête DAT Motion : attache, collecte, publie — puis s'efface.
 *
 * Démarrée avec la session (`GlassesVideoLink.attachHeadPose`, depuis
 * `GlassesController.attachLocked`), arrêtée avec elle (`detachHeadPose` via
 * `clear()`). Chaque
 * `MotionSample.orientation` donne une pose yeux (`HeadPoseMath`, pure) publiée
 * dans [GlassesVideoLink] ; orientation `null` (appareil sans fusion), erreur
 * Motion, refus d'attache (canal bêta, paire sans Motion) ou arrêt → `null`
 * publié : la sonde retombe sur le coupe-circuit D3 (sténopé seul), jamais sur
 * une valeur périmée. Aucune permission (Motion n'en demande pas).
 *
 * [attach] attache Motion sur la session (`session.addMotion(...).getOrNull()`
 * en prod) et [detach] la retire (`session.removeMotion()`) : injectés car la
 * session n'est pas instanciable en tests JVM.
 */
internal class GlassesHeadPose(
    private val scope: CoroutineScope,
    private val onNote: (GlassesMessage) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var motion: Motion? = null
    private var detach: (() -> Unit)? = null
    private var samplesJob: Job? = null
    private var errorsJob: Job? = null

    /** Attache Motion et collecte ; un refus publie `null` et s'annonce (pas de silence). */
    fun start(attach: () -> Motion?, detach: () -> Unit) {
        if (motion != null) return
        val attached = runCatching { attach() }.getOrNull()
        if (attached == null) {
            Log.w(TAG, "attache Motion refusée (canal bêta ? paire sans Motion ?) : sténopé seul")
            onNote(GlassesMessages.headPoseUnavailable())
            GlassesVideoLink.publishHeadPose(null, clockMs())
            return
        }
        motion = attached
        this.detach = detach
        samplesJob = scope.launch {
            attached.samples.collect { sample ->
                val q = sample.orientation
                val pose = if (q == null) null else HeadPoseMath.poseOrNull(q.x, q.y, q.z, q.w)
                GlassesVideoLink.publishHeadPose(pose, clockMs())
            }
        }
        errorsJob = scope.launch {
            // `drop(1)` : `errors` rejoue sa valeur courante à l'abonnement
            // (état pré-démarrage, pas un événement) — seules les erreurs qui
            // surviennent ensuite oublient la pose.
            attached.errors.drop(1).collect { error ->
                Log.w(TAG, "erreur Motion ($error) : pose tête oubliée, sténopé seul")
                GlassesVideoLink.publishHeadPose(null, clockMs())
            }
        }
        runCatching { attached.start() }.onFailure {
            Log.w(TAG, "démarrage Motion impossible : sténopé seul", it)
            stop()
        }
    }

    /** Coupe la collecte, arrête Motion, détache, publie `null`. Idempotent. */
    fun stop() {
        samplesJob?.cancel()
        samplesJob = null
        errorsJob?.cancel()
        errorsJob = null
        runCatching { motion?.stop() }
        motion = null
        runCatching { detach?.invoke() }
        detach = null
        GlassesVideoLink.publishHeadPose(null, clockMs())
    }
}
