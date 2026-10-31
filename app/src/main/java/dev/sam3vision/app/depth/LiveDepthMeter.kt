package dev.sam3vision.app.depth

import android.content.Context
import android.util.Log
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.depth.DepthEstimator
import dev.sam3vision.core.depth.DepthMap
import dev.sam3vision.core.depth.DepthReadings
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import kotlin.jvm.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Profondeur du direct : infère la dernière frame JPEG en tâche de fond
 * (~176 ms DA2, ~5 Hz face aux 7 i/s du direct) et affine les lectures
 * sténopé en mètres calibrés. Toujours active ; modèle manquant ou carte
 * périmée : le sténopé passe tel quel — le direct ne s'arrête jamais.
 * La carte périmée est signalée ([lastFreshness], journal), pas déguisée.
 */
class LiveDepthMeter(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val backend: () -> DepthBackend = { DepthBackend.DA2 },
) : DepthMeter {
    /**
     * Fraîcheur de la profondeur au dernier [readingsFor] : la carte périmée
     * ([STALE_MAP]) est signalée ici et journalisée, pas déguisée en sténopé.
     */
    enum class DepthFreshness {
        /** Médianes serveur de la frame : fraîches par construction. */
        SERVER,
        /** Carte embarquée de moins d'une seconde. */
        EMBEDDED,
        /** Carte embarquée périmée : sténopé tel quel, signalé. */
        STALE_MAP,
        /** Jamais de carte (moteur froid, modèle absent) : sténopé tel quel. */
        NO_MAP,
    }

    private val lock = Any()
    private var pendingJpeg: String? = null
    private var running = false
    private var latest: DepthMap? = null
    private var latestAtMs = 0L
    private var estimator: DepthEstimator? = null
    private var engineTriedMs = 0L

    /** Fraîcheur au dernier [readingsFor] (diagnostic, pastille future). */
    @Volatile
    var lastFreshness: DepthFreshness = DepthFreshness.NO_MAP
        private set

    override fun offer(jpegB64: String) {
        if (jpegB64.isBlank()) return
        synchronized(lock) {
            pendingJpeg = jpegB64
            if (running) return
            running = true
        }
        scope.launch(background) { pump() }
    }

    /**
     * Lectures affinées : les médianes du serveur (`with_depth`) d'abord — alignées
     * aux boîtes SAM de la frame, fraîches à chaque réponse —, la carte embarquée
     * sinon, ou le sténopé si elle manque ou a plus d'une seconde.
     */
    override fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading> {
        return try {
            val detections = frame.objects.toDetections(frame.width, frame.height)
            val server = DepthReadings.serverCloseness(frame.objects)
            if (server.isNotEmpty()) {
                lastFreshness = DepthFreshness.SERVER
                return DepthReadings.refineWithCloseness(detections, pinhole, server)
            }
            val map = freshMap()
            if (map == null) {
                if (hasMap()) {
                    lastFreshness = DepthFreshness.STALE_MAP
                    Log.w(TAG, "carte périmée : sténopé tel quel")
                } else {
                    lastFreshness = DepthFreshness.NO_MAP
                }
                return pinhole
            }
            lastFreshness = DepthFreshness.EMBEDDED
            DepthReadings.refine(frame.width, frame.height, detections, pinhole, map)
        } catch (e: Exception) {
            Log.w(TAG, "affinage: " + e.message)
            pinhole
        }
    }

    /** Moteur fermé, carte oubliée (rouvert à la prochaine frame si actif). */
    override fun reset() {
        val stale: DepthEstimator?
        synchronized(lock) {
            pendingJpeg = null
            latest = null
            stale = estimator
            estimator = null
        }
        // Hors verrou : `close()` ne bloque pas (libération reportée si run).
        if (stale != null) {
            stale.close()
        }
    }

    /** Vrai si une carte a déjà été inférée (fraîche ou périmée). */
    private fun hasMap(): Boolean = synchronized(lock) { latest != null }

    private fun freshMap(): DepthMap? {
        synchronized(lock) {
            if (freshnessOf(nowMs(), latestAtMs) != DepthFreshness.EMBEDDED) return null
            return latest
        }
    }

    private suspend fun pump() {
        try {
            for (i in 0 until DRAIN_ROUNDS) {
                val jpeg = takePending() ?: break
                infer(jpeg)
            }
        } finally {
            synchronized(lock) { running = false }
        }
    }

    private fun takePending(): String? = synchronized(lock) {
        val jpeg = pendingJpeg
        pendingJpeg = null
        jpeg
    }

    private suspend fun infer(jpegB64: String) {
        try {
            val engine = ensureEngine() ?: return
            val frame = DepthPixels.frame(jpegB64) ?: return
            val result = engine.estimate(frame.argb, frame.width, frame.height).getOrNull()
            if (result == null) return
            synchronized(lock) {
                latest = result.map
                latestAtMs = nowMs()
            }
        } catch (e: Exception) {
            Log.w(TAG, "inférence: " + e.message)
        }
    }

    private suspend fun ensureEngine(): DepthEstimator? {
        synchronized(lock) { estimator }?.let { return it }
        val target = backend()
        if (nowMs() - engineTriedMs < RETRY_MS) return null
        engineTriedMs = nowMs()
        val ensured = ModelDownloader.ensure(appContext, target)
        if (ensured.isFailure) {
            Log.w(TAG, "modèle " + target.code + " : " + ensured.exceptionOrNull()?.message)
            return null
        }
        val opened = DepthEstimators.open(appContext, target)
        if (opened is MissingAssetEstimator) {
            Log.w(TAG, "moteur " + target.code + " indisponible")
            return null
        }
        Log.i(TAG, "moteur " + opened.backend.code + " prêt")
        synchronized(lock) {
            estimator = opened
        }
        return opened
    }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    companion object {
        const val TAG = "DepthLive"
        private const val FRESH_MS = 1000L

        /**
         * Fraîcheur d'une carte inférée à [atMs], lue à [nowMs] : [DepthFreshness.EMBEDDED]
         * sous [FRESH_MS], [DepthFreshness.STALE_MAP] au-delà, [DepthFreshness.NO_MAP] sans
         * carte ([atMs] nul). Pure, testée sur JVM (l'horloge Android reste à [readingsFor]).
         */
        fun freshnessOf(nowMs: Long, atMs: Long, freshMs: Long = FRESH_MS): DepthFreshness {
            if (atMs <= 0L) return DepthFreshness.NO_MAP
            if (nowMs - atMs > freshMs) return DepthFreshness.STALE_MAP
            return DepthFreshness.EMBEDDED
        }
        private const val RETRY_MS = 60_000L
        private const val DRAIN_ROUNDS = 4
    }
}
