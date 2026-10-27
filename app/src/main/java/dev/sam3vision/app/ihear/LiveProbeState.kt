package dev.sam3vision.app.ihear

import android.graphics.Bitmap
import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.FrameTimings

/**
 * Ce que la page caméra montre de SAM : les masques décodés de la dernière frame,
 * les concepts reconnus, les distances mesurées, et les chiffres qui disent si ça
 * rame (cadence, latence moyenne et p95, durée serveur, taille de frame, GPU).
 *
 * [spoken] est la phrase que la voix doit dire (« Continue tout droit. » en guidage,
 * « Personne à gauche, à 1,4 mètre » en annonce des positions) : elle reste dans
 * l'état (rien ne se perd si l'écran se recompose) et change de valeur une seule
 * fois par nouvelle scène.
 */
data class LiveProbeState(
    val endpoint: String = "",
    val masks: List<Bitmap> = emptyList(),
    val labels: List<String> = emptyList(),
    /** Concepts envoyés à SAM (les suivis), pas seulement ceux reconnus. */
    val conceptsSent: List<String> = emptyList(),
    /** « ws » (WebSocket, le plus rapide) ou « http » (repli automatique). */
    val transport: String = TRANSPORT_WS,
    val objects: Int = 0,
    val fps: Float = 0f,
    val latencyMs: Long = 0,
    val p95Ms: Long = 0,
    val serverMs: Long = 0,
    /** Chronologie de la dernière frame (capture → pixel) + étages serveur reçus. */
    val timings: FrameTimings = FrameTimings(),
    val width: Int = 0,
    val height: Int = 0,
    val gpu: String = "",
    val frames: Int = 0,
    val distanceLayer: DistanceLayer? = null,
    val spoken: String? = null,
    /** Mode Traversée actif : prompts feu+voitures, guidage et annonces coupés. */
    val crossingActive: Boolean = false,
    /** Dernier état annoncé du feu (`null` = rien d'annoncé encore). */
    val crossing: dev.sam3vision.core.crossing.CrossingState? = null,
    /** Le conteneur Modal est en train de démarrer (30-50 s au premier appel). */
    val warming: Boolean = false,
    val error: String? = null,
    val debugVisible: Boolean = true,
) {
    /** Rien à afficher : ni frame analysée, ni erreur. */
    val idle: Boolean get() = frames == 0 && error == null

    /** L'objet le plus proche mesuré sur la dernière frame, `null` si rien n'est calibré. */
    val nearest: DistanceReading? get() = distanceLayer?.readings?.minByOrNull { it.meters }
}
