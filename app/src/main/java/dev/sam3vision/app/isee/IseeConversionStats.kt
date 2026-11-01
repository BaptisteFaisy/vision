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

    /** Frames vetoées par la garde nuit/noir (jamais envoyées à SAM) : comptées, loguées. */
    @Volatile
    var lowLightVetoes = 0L

    /** Frames floues (diagnostic seul, sans veto) : comptées dans le log de conversion. */
    @Volatile
    var blurryFrames = 0L

    /** Intervalle de la dernière frame analysée (ms), publié dans `capsplit` (`int`). */
    var lastIntervalMs = 0L

    /** Vrai quand la dernière frame était nuit/noir : la phrase ne part qu'à la transition. */
    @Volatile
    var wasDark = false

    /**
     * Passage au noir : le host y branche son rappel `onLowLight`, invoqué avec
     * [LOW_LIGHT_PHRASE] une fois par transition (pas de spam).
     */
    var onDarkTransition: (() -> Unit)? = null

    /**
     * Porte `wantsFrame` fermée : la frame est jetée (KEEP_ONLY_LATEST gardé), comptée
     * dans [droppedFrames] et loguée tous les [LOG_EVERY_DROPS] — pas de perte silencieuse.
     */
    fun noteDropped() {
        droppedFrames += 1
        if (droppedFrames % LOG_EVERY_DROPS == 0L) {
            android.util.Log.i("sam3vision", "analyse: $droppedFrames frames jetées (porte fermée), veto nuit $lowLightVetoes")
        }
    }

    /**
     * Garde nuit/noir : la frame ne part ni à SAM ni au picto (veto conservateur —
     * pas de description inventée sur du noir), comptée dans [lowLightVetoes]. La
     * phrase ne part qu'à la transition ([wasDark]) ; le direct aidant montre la
     * frame quand même (des pixels noirs honnêtes valent mieux que le silence).
     */
    fun noteLowLight() {
        lowLightVetoes += 1
        if (!wasDark) {
            wasDark = true
            onDarkTransition?.invoke()
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
            "conversion: ${ms} ms (capteur ${size.first}×${size.second}, jpeg ${bytes / 1024} Ko$direct, jetées $droppedFrames, veto nuit $lowLightVetoes, floues $blurryFrames)")
        android.util.Log.i(LiveLatencyLog.TAG, TimingsText.capsplit(ageMs, encMs, b64Ms, cbMs, ms, lastIntervalMs))
    }

    companion object {
        /** Une ligne de log de conversion toutes N frames (mesure, pas trace complète). */
        private const val LOG_EVERY_FRAMES = 20

        /** Une ligne de frames jetées tous les 60 (même cadence que le direct). */
        private const val LOG_EVERY_DROPS = 60L
    }
}
