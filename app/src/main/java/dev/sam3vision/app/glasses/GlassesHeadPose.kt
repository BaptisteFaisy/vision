package dev.sam3vision.app.glasses

import android.util.Log
import com.meta.wearable.dat.motion.Motion
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.HeadPoseMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale

/** Journal de la pose tête (recette : `logcat -s GlassesHeadPose`). */
private const val TAG = "GlassesHeadPose"

/** Orientations nulles d'affilée avant l'alerte « sans fusion » (~1 s à 10 Hz). */
private const val NO_FUSION_AFTER_NULLS = 10

/** Cadence du log comparatif de validation PITCH_SIGN (1 Hz). */
private const val PITCH_LOG_EVERY_MS = 1_000L

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
    private val eyeHeightM: () -> Float = { CameraPose.GLASSES_HEIGHT_M },
) {
    private var motion: Motion? = null
    private var detach: (() -> Unit)? = null
    private var samplesJob: Job? = null
    private var errorsJob: Job? = null
    private var nullStreak = 0
    private var lastPitchLogMs = 0L

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
        nullStreak = 0
        lastPitchLogMs = 0L
        samplesJob = scope.launch {
            attached.samples.collect { sample ->
                val q = sample.orientation
                if (q == null) {
                    onNullSample()
                } else {
                    nullStreak = 0
                    val pose = HeadPoseMath.poseOrNull(q.x, q.y, q.z, q.w, eyeHeightM())
                    logPitchCheck(q.x, q.y, q.z, q.w, clockMs())
                    GlassesVideoLink.publishHeadPose(pose, clockMs())
                }
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
        nullStreak = 0
        GlassesVideoLink.publishHeadPose(null, clockMs())
    }

    /**
     * Orientation nulle (appareil sans fusion) : `null` publié (sténopé seul),
     * et au seuil [NO_FUSION_AFTER_NULLS] une seule alerte — un état durable,
     * pas un raté. Réamorcée par la première orientation reçue.
     */
    private fun onNullSample() {
        nullStreak += 1
        if (nullStreak == NO_FUSION_AFTER_NULLS) {
            Log.w(TAG, "orientation nulle persistante (gen1/sans fusion ?) : stenope seul")
            onNote(GlassesMessages.headPoseNoFusion())
        }
        GlassesVideoLink.publishHeadPose(null, clockMs())
    }

    /**
     * Hook de validation [HeadPoseMath.PITCH_SIGN] : tangage brut vs signé à 1 Hz
     * (recette : `logcat -s GlassesHeadPose`). Protocole : sur paire réelle, un
     * hochement bas doit donner `signed > 0` ; si le signe est systématiquement
     * inverse, preuve labo → flip de PITCH_SIGN dans un correctif ultérieur seul.
     */
    private fun logPitchCheck(x: Float, y: Float, z: Float, w: Float, nowMs: Long) {
        if (nowMs - lastPitchLogMs < PITCH_LOG_EVERY_MS) return
        lastPitchLogMs = nowMs
        val raw = HeadPoseMath.rawPitchRadFromQuaternion(x, y, z, w)
        val signed = HeadPoseMath.pitchRadFromQuaternion(x, y, z, w)
        Log.d(TAG, "pitch raw=%.4f signed=%.4f q=(%.3f,%.3f,%.3f,%.3f)".format(Locale.US, raw, signed, x, y, z, w))
    }
}
