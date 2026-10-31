package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box

/**
 * Concepts véhicules du veto trafic : ce que SAM doit suivre en mode traversée.
 *
 * Les sept véhicules du contrat, en anglais (la forme que SAM segmente le mieux).
 * Le français reste reconnu côté veto ([isVehicle]) : un label « voiture » bloque
 * comme un « car », « trottinette » comme un « scooter ».
 */
object VehicleConcepts {

    /** Les sept véhicules du contrat : voiture, bus, camion, moto, vélo et trottinette. */
    val PROMPTS: List<String> = listOf(
        "car", "bus", "truck", "motorcycle", "bicycle", "scooter", "electric scooter",
    )

    private val KEYS = setOf(
        "car", "bus", "buses", "truck", "motorcycle", "bicycle", "bike",
        "scooter", "electric scooter", "trottinette", "trottinette electrique",
        "voiture", "camion", "moto", "velo",
    )

    /** Vrai si [label] désigne un véhicule (casse, accents, pluriel simple neutralisés). */
    fun isVehicle(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in KEYS) return true
        return key.endsWith("s") && key.length > 3 && key.dropLast(1) in KEYS
    }

    /**
     * Prompts du mode traversée : véhicules d'abord, [base] ensuite, bornés à
     * [LivePrompts.MAX] — seize concepts, donc les sept véhicules plus toute
     * la base du direct (dangers, feu, passage…).
     */
    fun promptsFor(base: List<String> = emptyList()): List<String> =
        LivePrompts.normalize(PROMPTS + base)
}

/** Phase de la traversée, du point de vue du piéton qui attend au bord du trottoir. */
enum class TraversePhase {
    /** Feu rouge, ou veto trafic : ne pas traverser. */
    ATTENDRE,

    /** Vert confirmé, sans veto, première frame : se préparer, pas encore partir. */
    PRET,

    /** Vert confirmé et maintenu sans veto : traverser. */
    TRAVERSER,

    /** Un véhicule menace alors qu'on traversait ou s'apprêtait à le faire : danger. */
    ALERTE,
}

/**
 * Décision de traversée pour une frame : la phase, le mot d'ordre vocal minimal et
 * le détail du veto pour le câblage (voix, guidage obstacles).
 */
data class TraverseDecision(
    val state: TraversePhase,
    /** Mot d'ordre (« wait », « ready », « cross ») — la voix l'habille. */
    val cue: String,
    /** Vrai quand un véhicule force l'attente malgré un vert éventuel. */
    val veto: Boolean,
    /** Libellé du véhicule bloquant, `null` sans veto. */
    val blockingLabel: String?,
    /** Distance sténopé du bloquant (m), `null` si non mesurable. */
    val blockingMeters: Float?,
    /** Temps-avant-collision du plus dangereux (`null` sans approche suivie). */
    val approachTtcSeconds: Float? = null,
    /**
     * Vrai quand un objet proche (véhicule ou non) est mesuré : le guidage
     * obstacles doit rester actif malgré le mode traversée — on ne coupe jamais
     * tout le guidage devant un objet proche, l'exclusion est levée.
     */
    val keepObstacleGuidance: Boolean,
)

/**
 * Veto trafic de la traversée : un véhicule qui approche ou qui est proche force
 * « attendez », même sur vert confirmé.
 *
 * Un véhicule bloque quand son score dépasse [SCORE_MIN] **et** qu'il approche
 * (surface de boîte croissante sur deux frames) **ou** qu'il est proche (distance
 * sténopé sous [VETO_METERS]). L'approche se lit sur les boîtes seules — sans
 * focale, elle reste le seul critère ; la distance réutilise [KnownSizes] (déjà
 * calibré pour les sept véhicules) et [Pinhole].
 *
 * Machine d'état : [TraversePhase.ATTENDRE] par défaut et sous veto, `PRET` à la
 * première frame verte sans veto, `TRAVERSER` quand le vert se maintient, `ALERTE`
 * quand le veto frappe en pleine traversée (ou juste avant). Après une alerte, le
 * vert doit se reconfirmer (`PRET`) avant de repartir.
 *
 * Pur Kotlin testable en JVM : aucun Android, aucune horloge — le feu vert vient
 * du module feu (côté `crossing/`), la focale du calibrage caméra.
 */
class TraverseVeto {

    /** Phase courante (celle de la dernière frame traitée). */
    var currentState: TraversePhase = TraversePhase.ATTENDRE
        private set

    /** Surface max par véhicule à la frame précédente (clé : label singulier canonique). */
    private var previousAreas: Map<String, Float> = emptyMap()

    /** Suivi temporel des véhicules (IoU + TTC) — ceinture et bretelles du veto. */
    private val approach = VehicleApproach()

    /** Oublie la phase, la frame précédente et les pistes (nouvelle traversée). */
    fun reset() {
        currentState = TraversePhase.ATTENDRE
        previousAreas = emptyMap()
        approach.reset()
    }

    /**
     * Décision pour une frame. [lightGreen] dit si le vert est confirmé,
     * [detections] porte les boîtes SAM, [focalPx] la focale en pixels de l'image
     * analysée (≤ 0 : distance indisponible, seule l'approche compte), [atMs]
     * la date de la frame (< 0 : suivi temporel désactivé, veto instantané seul).
     */
    fun update(
        lightGreen: Boolean,
        detections: List<Detection>,
        focalPx: Float,
        atMs: Long = -1L,
    ): TraverseDecision {
        val blocking = findBlocking(detections, focalPx)
        previousAreas = vehicleAreas(detections)
        val tracked = approachVerdict(detections, atMs)
        val veto = blocking != null || tracked.approaching
        currentState = nextState(lightGreen, veto)
        return TraverseDecision(
            state = currentState,
            cue = cueOf(currentState),
            veto = veto,
            blockingLabel = blocking?.label,
            blockingMeters = blocking?.meters,
            approachTtcSeconds = tracked.ttcSeconds,
            keepObstacleGuidance = hasNearObject(detections, focalPx),
        )
    }

    /** Approche suivie, ou silence si la frame n'est pas datée. */
    private fun approachVerdict(detections: List<Detection>, atMs: Long): ApproachVerdict {
        if (atMs < 0L) return ApproachVerdict(false, null, null)
        return approach.update(detections, atMs)
    }

    /** Le véhicule bloquant le plus proche, `null` sans veto. */
    private fun findBlocking(detections: List<Detection>, focalPx: Float): Blocking? =
        detections.mapNotNull { vetoCandidate(it, focalPx) }
            .minByOrNull { it.meters ?: Float.MAX_VALUE }

    /** Candidature d'une détection au veto : véhicule confiant qui approche ou proche. */
    private fun vetoCandidate(detection: Detection, focalPx: Float): Blocking? {
        if (!VehicleConcepts.isVehicle(detection.label)) return null
        if (detection.score <= SCORE_MIN) return null
        val area = Box.fromProto(detection.box)?.area
        val previous = previousAreas[vehicleKey(detection.label)]
        val approaching = area != null && previous != null && area > previous
        val meters = distanceOf(detection, focalPx)
        val near = meters != null && meters < VETO_METERS
        if (!approaching && !near) return null
        return Blocking(detection.label, meters)
    }

    /** Surface max par véhicule de la frame (les doublons de label fusionnent). */
    private fun vehicleAreas(detections: List<Detection>): Map<String, Float> =
        detections.mapNotNull { detection ->
            if (!VehicleConcepts.isVehicle(detection.label)) return@mapNotNull null
            val area = Box.fromProto(detection.box)?.area ?: return@mapNotNull null
            vehicleKey(detection.label) to area
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, areas) -> areas.max() }

    /** Distance sténopé d'une détection, `null` si focale ou calibrage manquant. */
    private fun distanceOf(detection: Detection, focalPx: Float): Float? {
        if (focalPx <= 0f) return null
        val realHeight = KnownSizes.heightMeters(detection.label) ?: return null
        return Pinhole.distanceMeters(detection.box, realHeight, focalPx)
    }

    /** Un objet calibré proche (véhicule ou non) : le guidage obstacles reste actif. */
    private fun hasNearObject(detections: List<Detection>, focalPx: Float): Boolean =
        detections.any { detection ->
            detection.score > SCORE_MIN &&
                distanceOf(detection, focalPx)?.let { it < VETO_METERS } == true
        }

    /** Transition : le veto prime sur le vert, l'alerte exige une reconfirmation. */
    private fun nextState(lightGreen: Boolean, veto: Boolean): TraversePhase = when {
        veto && (currentState == TraversePhase.PRET || currentState == TraversePhase.TRAVERSER) ->
            TraversePhase.ALERTE
        veto -> TraversePhase.ATTENDRE
        lightGreen &&
            (currentState == TraversePhase.PRET || currentState == TraversePhase.TRAVERSER) ->
            TraversePhase.TRAVERSER
        lightGreen -> TraversePhase.PRET
        else -> TraversePhase.ATTENDRE
    }

    private fun cueOf(state: TraversePhase): String = when (state) {
        TraversePhase.ATTENDRE, TraversePhase.ALERTE -> "wait"
        TraversePhase.PRET -> "ready"
        TraversePhase.TRAVERSER -> "cross"
    }

    /** Clé de suivi d'un véhicule entre deux frames (« Cars » et « car » fusionnent). */
    private fun vehicleKey(label: String): String {
        val key = KnownSizes.canonical(label)
        if (key.endsWith("s") && key.length > 3) return key.dropLast(1)
        return key
    }

    /** Un véhicule qui bloque : son label et sa distance si elle est mesurable. */
    private data class Blocking(val label: String, val meters: Float?)

    companion object {
        /** Score de confiance minimal d'un véhicule pris au sérieux (strictement dépassé). */
        const val SCORE_MIN = 0.5f

        /** Sous cette distance sténopé (m), un véhicule bloque même sans approche visible. */
        const val VETO_METERS = 15f
    }
}
