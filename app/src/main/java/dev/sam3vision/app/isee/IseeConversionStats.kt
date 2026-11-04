package dev.sam3vision.app.isee

import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.live.TimingsText
import dev.sam3vision.core.local.LumaPlane

/**
 * Compteurs de conversion des frames iSee et leur journal périodique, extraits de
 * `IseeCameraHost` (plafond 500 lignes). Le host n'en garde que des délégateurs de
 * même nom : aucun appelant ne change.
 */
internal class IseeConversionStats {

    /** Frames converties depuis la liaison caméra (le log de coût ne tire qu'un coup sur 20). */
    var conversionFrames = 0

    /** Frames jetées par la porte `wantsFrame` (KEEP_ONLY_LATEST gardé) : comptées, loguées. */
    @Volatile
    var droppedFrames = 0L

    /** Frames floues (diagnostic seul, sans veto) : comptées dans le log de conversion. */
    @Volatile
    var blurryFrames = 0L

    /** Intervalle de la dernière frame analysée (ms), publié dans `capsplit` (`int`). */
    var lastIntervalMs = 0L

    /**
     * Porte `wantsFrame` fermée : la frame est jetée (KEEP_ONLY_LATEST gardé), comptée
     * dans [droppedFrames] et loguée tous les [LOG_EVERY_DROPS] — pas de perte silencieuse.
     */
    fun noteDropped() {
        droppedFrames += 1
        if (droppedFrames % LOG_EVERY_DROPS == 0L) {
            android.util.Log.i("sam3vision", "analyse: $droppedFrames frames jetées (porte fermée)")
        }
    }

    /** Piqué de la frame (diagnostic seul) : floue sous [BLUR_VAR_MIN], sans veto. */
    fun noteSharpness(luma: ByteArray) {
        if (laplacianVariance(luma, LumaPlane.WIDTH, LumaPlane.HEIGHT) < BLUR_VAR_MIN) blurryFrames += 1
    }

    /**
     * Log périodique de la conversion : un toutes les [LOG_EVERY_FRAMES] frames.
     * La décomposition `capsplit` part sous le tag [LiveLatencyLog.TAG] : même dump
     * `ISEE_LAT` que la sonde, jointure temporelle directe avec le poste `capt`.
     */
    fun countConversion(frames: Int, ms: Long, size: Pair<Int, Int>, bytes: Int, shareBytes: Int,
        ageMs: Long, encMs: Long, b64Ms: Long, cbMs: Long) {
        if (frames % LOG_EVERY_FRAMES != 0) return
        val direct = if (shareBytes >= 0) ", direct ${shareBytes / 1024} Ko" else ""
        android.util.Log.i("sam3vision",
            "conversion: ${ms} ms (capteur ${size.first}×${size.second}, jpeg ${bytes / 1024} Ko$direct, jetées $droppedFrames, floues $blurryFrames)")
        android.util.Log.i(LiveLatencyLog.TAG, TimingsText.capsplit(ageMs, encMs, b64Ms, cbMs, ms, lastIntervalMs))
    }

    companion object {
        /** Une ligne de log de conversion toutes N frames (mesure, pas trace complète). */
        private const val LOG_EVERY_FRAMES = 20

        /** Une ligne de frames jetées tous les 60 (même cadence que le direct). */
        private const val LOG_EVERY_DROPS = 60L
    }
}
