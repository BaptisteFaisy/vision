package dev.sam3vision.app.isee

import dev.sam3vision.app.live.MotionLog
import dev.sam3vision.app.traverse.backgroundOf
import dev.sam3vision.core.live.MotionGapStats
import dev.sam3vision.core.traverse.BackgroundFrame
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.motion.GyroMotion
import dev.sam3vision.core.traverse.MovingVerdict
import dev.sam3vision.core.traverse.Verdict
import dev.sam3vision.core.motion.BlockMotionSource
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Mouvement global fond côté sonde : `LiveProbe.onFrame` offre son JPEG ici,
 * les veilleurs y lisent le dernier [FrameMotion] — même estimateur JVM que
 * la traversée, une instance pour le fil de la pompe (l'estimateur n'est pas
 * sûr entre threads). Wrapper plateforme (décodage), pompe testée en JVM avec
 * décodeur et coureur faux (`ProbeBackgroundMotionTest`).
 */
internal object ProbeBackgroundMotion {

    private val source = BlockMotionSource()
    private var prev: BackgroundFrame? = null
    private var prevAtMs: Long = 0L

    /** Dernier mouvement global estimé (`null` = pas de paire ou estimateur muet). */
    @Volatile
    var lastMotion: FrameMotion? = null
        private set

    /** Dernier gyro daté (`null` = pas de signal) : repli des verdicts quand le fond optique est muet. */
    @Volatile
    var lastGyro: GyroMotion? = null
        private set

    /** Fenêtre glissante des écarts boîte-fond (px) : le panneau debug y lit moy/p95. */
    private val gaps = MotionGapStats()

    /**
     * Derniers chiffres mouvement publiés (`null` = rien de jugé) : lus par
     * l'émission d'état suivante — le panneau a une frame de retard, assumé.
     */
    @Volatile
    var lastDebug: MotionDebug? = null
        private set

    /** Dépôt latest-only : le fil d'analyse n'y pose qu'une référence (cf. [offer]). */
    private val lock = Any()
    private var pendingJpeg: String? = null
    private var pendingAtMs: Long = 0L
    private var draining = false

    /**
     * Génération du dépôt : chaque [reset] l'incrémente, un traitement issu
     * d'une génération périmée est jeté (jamais publié après un reset).
     */
    private var epoch: Long = 0L

    /**
     * Pompe du fond : un fil unique — l'estimateur n'est pas sûr entre threads,
     * et le séquentiel préserve l'appariement prev/cur. Démon : il ne retient
     * pas le process, et les tests le remplacent par [drainRunner].
     */
    private val worker: Executor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "probe-motion").apply { isDaemon = true }
        }
    }

    /** Exécute la pompe (défaut : [worker]) : les tests y branchent un coureur piloté. */
    internal var drainRunner: (Runnable) -> Unit = { worker.execute(it) }

    /** JPEG base64 → luminance 96 px (défaut : [backgroundOf]) : les tests y branchent un faux. */
    internal var decoder: (String) -> BackgroundFrame? = ::backgroundOf

    /**
     * Offre une frame datée de [atMs] (ms) : dépôt immédiat, estimation hors
     * fil — le fil d'analyse ne paie que la référence (le décodage JPEG et le
     * bloc-matching synchrones y coûtaient ~19 ms par frame admise, poste `cb`).
     *
     * Latest-only : si la pompe traite encore la précédente, les intermédiaires
     * sont jetées sans décodage (la plus récente gagne, comme la réserve SAM) —
     * les veilleurs lisent [lastMotion] au rythme des réponses, pas des frames.
     */
    fun offer(jpegB64: String, atMs: Long) {
        synchronized(lock) {
            pendingJpeg = jpegB64
            pendingAtMs = atMs
            if (draining) return
            draining = true
        }
        drainRunner(Runnable { drainLoop() })
    }

    /** Vide le dépôt, une frame à la fois, jusqu'à ce qu'il soit vide (cf. [offer]). */
    private fun drainLoop() {
        while (true) {
            val job = takePending() ?: return
            estimate(job)
        }
    }

    /** Retire le dépôt (vide = la pompe s'arrête) ; `null` = rien à traiter. */
    private fun takePending(): PendingFrame? = synchronized(lock) {
        val jpeg = pendingJpeg
        if (jpeg == null) {
            draining = false
            return@synchronized null
        }
        pendingJpeg = null
        PendingFrame(jpeg, pendingAtMs, epoch)
    }

    /**
     * Décode puis estime une frame déposée : décodage et bloc-matching hors
     * verrou (le dépôt du fil d'analyse n'attend jamais), seule la bascule
     * prev/cur est protégée. Génération périmée ([reset] entre le dépôt et le
     * traitement) : jetée sans décodage ni publication.
     */
    private fun estimate(job: PendingFrame) {
        if (!currentEpoch(job.epoch)) return
        val cur = decoder(job.jpegB64)
        val snap = synchronized(lock) {
            if (epoch != job.epoch) return
            val previous = prev
            val dtMs = job.atMs - prevAtMs
            prev = cur
            prevAtMs = job.atMs
            Pair(previous, dtMs)
        }
        val (previous, dtMs) = snap
        val motion = if (previous != null && cur != null &&
            previous.width == cur.width && previous.height == cur.height
        ) {
            source.estimateSince(previous.toGray(), cur.toGray())
                ?.let { FrameMotion(it, cur.width, cur.height, dtMs) }
        } else {
            null
        }
        synchronized(lock) {
            if (epoch == job.epoch) lastMotion = motion
        }
    }

    /** Vrai si [jobEpoch] est la génération courante (le dépôt n'a pas été reseté). */
    private fun currentEpoch(jobEpoch: Long): Boolean = synchronized(lock) { epoch == jobEpoch }

    /** Nouvelle séance : dépôt jeté, prochaine frame sans prev (les traitements en vol sont jetés). */
    fun reset() {
        synchronized(lock) {
            pendingJpeg = null
            prev = null
            lastMotion = null
            lastGyro = null
            lastDebug = null
            epoch += 1
        }
        gaps.reset()
    }

    /**
     * Pose le gyro daté de la frame ([EgoMotion.hintFor] + grille luma + dt inter-frames,
     * `null` = pas d'avis : capteur absent, delta périmé ou rotation excessive).
     * Lu avec [lastMotion] par les veilleurs véhicules (repli gyro quand le fond est muet).
     */
    fun noteGyro(gyro: GyroMotion?) {
        lastGyro = gyro
    }

    /** Enregistre les écarts (px) et expose les chiffres au panneau debug. */
    fun noteGaps(label: String, gapPx: List<Float>) {
        gapPx.forEach(gaps::record)
        lastDebug = MotionDebug(label, 0f, gaps.averagePx, gaps.p95Px)
    }

    /** Frame retirée du dépôt : son JPEG, sa date (ms) et la génération du dépôt (cf. [reset]). */
    private class PendingFrame(val jpegB64: String, val atMs: Long, val epoch: Long)
}

/**
 * Journalise les transitions mouvement du direct ([MotionLog]) : « caméra
 * bouge » quand le porteur bouge, « voiture bouge » quand une piste roule —
 * silence sinon (arrêt, doute, pas de piste). [frameWidth] et [dtMs]
 * convertissent l'écart boîte-fond (fractions/s) en px par frame ;
 * `bg=0` : le score de changement du fond n'est pas calculé en V1.
 */
internal fun logMotionTransition(
    cameraMoved: Boolean,
    verdicts: Collection<MovingVerdict>,
    frameWidth: Int,
    dtMs: Long,
) {
    if (verdicts.isEmpty() || frameWidth <= 0 || dtMs <= 0L) return
    val label = when {
        cameraMoved -> "caméra bouge"
        verdicts.any { it.verdict == Verdict.VEHICLE_MOVED } -> "voiture bouge"
        else -> return
    }
    val dtSeconds = dtMs / 1000f
    val gaps = verdicts.map { hypot(it.boxDx - it.globalDx, it.boxDy - it.globalDy) * frameWidth * dtSeconds }
    val sorted = gaps.sorted()
    MotionLog.logTransition(label, 0f, gaps.average().toFloat(), sorted[(sorted.size * 95 / 100).coerceIn(0, sorted.size - 1)])
    ProbeBackgroundMotion.noteGaps(label, gaps)
}
