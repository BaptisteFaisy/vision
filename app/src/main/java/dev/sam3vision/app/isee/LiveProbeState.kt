package dev.sam3vision.app.isee

import android.graphics.Bitmap
import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCue
import dev.sam3vision.core.model.Passage

/**
 * Ce que la page caméra montre de SAM : les masques décodés de la dernière frame,
 * les concepts reconnus, les distances mesurées, et les chiffres qui disent si ça
 * rame (cadence, latence moyenne et p95, durée serveur, taille de frame, GPU).
 *
 * [spoken] est la phrase que la voix doit dire (« Tout droit. » en guidage,
 * « Personne à 9 heures, à 1,4 mètre » en annonce des positions) : elle reste dans
 * l'état (rien ne se perd si l'écran se recompose). [spokenSeq] compte ses
 * émissions : une direction stable redite à l'identique garde le même [spoken]
 * mais incrémente [spokenSeq], pour que la voix la redise au lieu de l'avaler
 * (`distinctUntilChanged`). [steering] est le chemin calculé pour la dernière
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
    /**
     * Compteur d'émissions de [spoken] : incrémenté à chaque phrase produite,
     * même à l'identique (direction stable redite en continu). La voix
     * observe le triplet ([spoken], [spokenUrgent], [spokenSeq]) : sans lui,
     * une répétition à l'identique serait avalée par `distinctUntilChanged`.
     */
    val spokenSeq: Long = 0,
    /**
     * [spoken] est-elle le mot stop ? Publié avec la phrase, dans la même
     * écriture : la voix coupe la lecture en cours pour le dire (chat
     * compris), sans relire un état qui aurait déjà bougé. Faux pour les
     * mots doux (gauche/tout droit/droite), les positions et la Traversée
     * (balise nulle) : ils attendent le silence.
     */
    val spokenUrgent: Boolean = false,
    /** Cap demandé à la voix (tout droit par défaut) : le couloir que le guidage marche. */
    val steeringCap: SteeringCap = SteeringCap.Straight,
    /** Chemin de la dernière frame (`null` = guidage coupé ou mode Traversée). */
    val steering: SteeringCue? = null,
    /** Passage conseillé par SAM (`null` = backend antérieur ou pas de frame). */
    val samPassage: Passage? = null,
    /**
     * Consigne du battement 500 ms (`null` = guidage coupé ou pas encore de
     * frame) : mot + côté + mètres, lus sur la vidéo ([InstructionLayer]).
     */
    val pulse: GuidancePulse? = null,
    /** Détour obstacle subi : le GPS recalcule l'itinéraire s'il dure (faux en Traversée). */
    val detour: Boolean = false,
    /** Mode Traversée actif : mêmes masques, guidage coupé, feu et véhicules annoncés. */
    val crossingActive: Boolean = false,
    /** Dernier état annoncé du feu (`null` = rien d'annoncé encore). */
    val crossing: dev.sam3vision.core.crossing.CrossingState? = null,
    /**
     * Boîte normalisée du feu de la dernière frame (`x1,y1,x2,y2` en 0..1), pour
     * poser l'état **sur la vidéo** ([CrossingOverlayLayer]). `null` = aucun feu
     * dans le champ, ou mode coupé : rien à marquer.
     */
    val crossingBox: List<Float>? = null,
    /**
     * Phase prudente du verdict direct (`null` = rien de publié encore) : seul
     * [Traverser][dev.sam3vision.core.traverse.TraversePhase.Traverser] vaut
     * « peut traverser », toute autre phase vaut « ne peut pas traverser ».
     */
    val traversePhase: dev.sam3vision.core.traverse.TraversePhase? = null,
    /** Un passage piéton est dans le champ : le mode Traversée s'arme seul (retombe quand le passage disparaît). */
    val crossingOffer: Boolean = false,
    /** Passage piéton vu sur la dernière frame publiée (brut, sans mémoire ni silence). */
    val crossingVisible: Boolean = false,
    /** Le conteneur Modal est en train de démarrer (30-50 s au premier appel). */
    val warming: Boolean = false,
    val error: String? = null,
    /**
     * Alerte danger du pipeline urgence (« ⚠ Personne droit devant — contact
     * dans ~3 s »), `null` quand personne ne vient. La voix la lira plus tard.
     */
    val dangerAlert: String? = null,
    /**
     * Chiffres mouvement V1 (fond contre boîte) de la dernière frame (`null` = pas
     * d'estimation publiée) : verdict, score de changement du fond et écart
     * global-boîte, lus par le panneau debug ([liveProbeMotionLine]).
     */
    val motion: MotionDebug? = null,
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

/**
 * Chiffres mouvement V1 exposés au panneau debug : [verdict] (« voiture bouge » ou
 * « caméra bouge », étiquette posée par le producteur), [bgChange] (score de changement
 * du fond) et écart global-boîte ([gapMeanPx]/[gapP95Px], en px).
 */
data class MotionDebug(
    val verdict: String = "",
    val bgChange: Float = 0f,
    val gapMeanPx: Float = 0f,
    val gapP95Px: Float = 0f,
)
