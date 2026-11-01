package dev.sam3vision.app.isee

import dev.sam3vision.app.live.MotionLog
import dev.sam3vision.app.traverse.backgroundOf
import dev.sam3vision.core.live.MotionGapStats
import dev.sam3vision.core.traverse.BackgroundFrame
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.MovingVerdict
import dev.sam3vision.core.traverse.Verdict
import dev.sam3vision.core.motion.BlockMotionSource
import kotlin.math.hypot

/**
 * Mouvement global fond côté sonde : `LiveProbe.onFrame` offre son JPEG ici,
 * les veilleurs y lisent le dernier [FrameMotion] — même estimateur JVM que
 * la traversée, une instance pour le thread caméra (l'estimateur n'est pas
 * sûr entre threads). Wrapper plateforme (décodage), non testé en JVM comme
 * `CrossingPixels.frame`.
 */
internal object ProbeBackgroundMotion {

    /**
     * Fil dédié : `backgroundOf` décode un JPEG entier (base64 → bitmap → échelle →
     * luminance) et l'appelant est le thread de l'analyseur caméra. Le faire ici ne
     * coûtait que ~22 ms par frame sur un budget de 33 ms.
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { t ->
        Thread(t, "probe-bg-motion").apply { isDaemon = true }
    }

    /** Une seule frame en cours : au-delà, on laisse tomber (jamais d'empilement). */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)


    private val source = BlockMotionSource()
    private var prev: BackgroundFrame? = null
    private var prevAtMs: Long = 0L

    /** Dernier mouvement global estimé (`null` = pas de paire ou estimateur muet). */
    var lastMotion: FrameMotion? = null
        private set

    /** Fenêtre glissante des écarts boîte-fond (px) : le panneau debug y lit moy/p95. */
    private val gaps = MotionGapStats()

    /**
     * Derniers chiffres mouvement publiés (`null` = rien de jugé) : lus par
     * l'émission d'état suivante — le panneau a une frame de retard, assumé.
     */
    var lastDebug: MotionDebug? = null
        private set

    /**
     * Offre une frame datée de [atMs] (ms) : estime le mouvement depuis la
     * précédente et le retient dans [lastMotion] (`null` = pas d'avis :
     * première frame, recadrage ou fond plat).
     */
    /**
     * Comme [offer], mais **sans bloquer l'appelant** : le décodage part sur [worker].
     * Une frame pendant qu'une autre se décode est ignorée — l'estimation compare des
     * paires consécutives et tolère un trou (`offer` rend `null` quand il n'a pas
     * d'avis), un empilement serait pire que le trou.
     */
    fun post(jpegB64: String, atMs: Long) {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                offer(jpegB64, atMs)
            } catch (_: Throwable) {
                // frame illisible : la suivante arrive
            } finally {
                busy.set(false)
            }
        }
    }

    fun offer(jpegB64: String, atMs: Long): FrameMotion? {
        val cur = backgroundOf(jpegB64)
        val previous = prev
        prev = cur
        val motion = if (previous != null && cur != null &&
            previous.width == cur.width && previous.height == cur.height
        ) {
            source.estimateSince(previous.toGray(), cur.toGray())
                ?.let { FrameMotion(it, cur.width, cur.height, atMs - prevAtMs) }
        } else {
            null
        }
        prevAtMs = atMs
        lastMotion = motion
        return motion
    }

    /** Nouvelle séance : la prochaine frame n'a pas de prev. */
    fun reset() {
        prev = null
        lastMotion = null
        lastDebug = null
        gaps.reset()
    }

    /** Enregistre les écarts (px) et expose les chiffres au panneau debug. */
    fun noteGaps(label: String, gapPx: List<Float>) {
        gapPx.forEach(gaps::record)
        lastDebug = MotionDebug(label, 0f, gaps.averagePx, gaps.p95Px)
    }
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
