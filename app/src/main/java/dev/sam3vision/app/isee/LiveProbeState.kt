package dev.sam3vision.app.isee

import android.graphics.Bitmap
import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCue

/**
 * Ce que la page caméra montre de SAM : les masques décodés de la dernière frame,
 * les concepts reconnus, les distances mesurées, et les chiffres qui disent si ça
 * rame (cadence, latence moyenne et p95, durée serveur, taille de frame, GPU).
 *
 * [spoken] est la phrase que la voix doit dire (« Continue. » en guidage,
 * « Personne à 9 heures, à 1,4 mètre » en annonce des positions) : elle reste dans
 * l'état (rien ne se perd si l'écran se recompose) et change de valeur une seule
 * fois par nouvelle scène. [steering] est le chemin calculé pour la dernière
 * frame, dessiné sur la vidéo à chaque frame (la vidéo est en direct, la voix
 * seule est débouncée).
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
    /** Les deux modèles LLM effectifs (`debugModelsLine`) : vide = non renseigné. */
    val modelsLine: String = "",
    val frames: Int = 0,
    val distanceLayer: DistanceLayer? = null,
    val spoken: String? = null,
    /** Cap demandé à la voix (tout droit par défaut) : le couloir que le guidage marche. */
    val steeringCap: SteeringCap = SteeringCap.Straight,
    /** Chemin de la dernière frame (`null` = guidage coupé ou mode Traversée). */
    val steering: SteeringCue? = null,
    /** Mode Traversée actif : prompts feu+voitures, guidage et annonces coupés. */
    val crossingActive: Boolean = false,
    /** Dernier état annoncé du feu (`null` = rien d'annoncé encore). */
    val crossing: dev.sam3vision.core.crossing.CrossingState? = null,
    /**
     * Boîte normalisée du feu de la dernière frame (`x1,y1,x2,y2` en 0..1), pour
     * poser l'état **sur la vidéo** ([CrossingOverlayLayer]). `null` = aucun feu
     * dans le champ, ou mode coupé : rien à marquer.
     */
    val crossingBox: List<Float>? = null,
    /** Un passage piéton est dans le champ : proposer le mode Traversée (jamais automatique). */
    val crossingOffer: Boolean = false,
    /** Le conteneur Modal est en train de démarrer (30-50 s au premier appel). */
    val warming: Boolean = false,
    val error: String? = null,
) {
    /** Rien à afficher : ni frame analysée, ni erreur. */
    val idle: Boolean get() = frames == 0 && error == null

    /**
     * Masques et chiffres effacés, endpoint/GPU/concepts et mode Traversée gardés
     * (début, sortie d'écran).
     */
    fun cleared(): LiveProbeState = LiveProbeState(
        endpoint = endpoint,
        gpu = gpu,
        modelsLine = modelsLine,
        conceptsSent = conceptsSent,
        crossingActive = crossingActive,
    )

    /** L'objet le plus proche mesuré sur la dernière frame, `null` si rien n'est calibré. */
    val nearest: DistanceReading? get() = distanceLayer?.readings?.minByOrNull { it.meters }
}
