package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PersonLabels
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import kotlin.math.hypot

/**
 * Concepts véhicules du veto trafic : ce que SAM doit suivre en mode traversée.
 *
 * Les trois véhicules du contrat (31/10, ordre user : camion, moto et
 * trottinettes sortis, voiture/bus/vélo gardés), en anglais (la forme que
 * SAM segmente le mieux).
 * Le français reste reconnu côté veto ([isVehicle]) : un label « voiture » bloque
 * comme un « car », « trottinette » comme un « scooter ».
 */
object VehicleConcepts {

    /** Les trois véhicules du contrat : voiture, bus et vélo. */
    val PROMPTS: List<String> = listOf("car", "bus", "bicycle")

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
     * Clé de suivi d'un véhicule entre deux frames (« Cars » et « car »
     * fusionnent) : même clé côté veto instantané et pistes suivies, pour que
     * le veto fond sache quelles croissances taire.
     */
    fun keyOf(label: String): String {
        val key = KnownSizes.canonical(label)
        if (key.endsWith("s") && key.length > 3) return key.dropLast(1)
        return key
    }

    /**
     * Prompts du mode traversée : véhicules d'abord, [base] ensuite, bornés à
     * [LivePrompts.MAX] — dix-huit concepts, donc les trois véhicules plus toute
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
 * calibré pour les sept véhicules) et [Pinhole]. Un véhicule confirmé à
 * l'arrêt (mesuré, centré, immobile, seau stable — voir [stoppedKeys]) ne
 * bloque plus : le vert seul décide alors ; en mouvement ou doute bloque.
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

    /** Dernier centre/seau/date par véhicule (clé : label singulier canonique). */
    private var previousStill: Map<String, StillMemory> = emptyMap()

    /** Suivi temporel des véhicules (IoU + TTC) — ceinture et bretelles du veto. */
    private val approach = VehicleApproach()

    /** Oublie la phase, la frame précédente et les pistes (nouvelle traversée). */
    fun reset() {
        currentState = TraversePhase.ATTENDRE
        previousAreas = emptyMap()
        previousStill = emptyMap()
        approach.reset()
    }

    /**
     * Décision pour une frame. [lightGreen] dit si le vert est confirmé,
     * [detections] porte les boîtes SAM, [focalPx] la focale en pixels de l'image
     * analysée (≤ 0 : distance indisponible, seule l'approche compte), [atMs]
     * la date de la frame (< 0 : suivi temporel désactivé, veto instantané seul).
     * [motion] (`null` = pas de signal fond) fait taire l'approche suivie des
     * pistes qui suivent la caméra, ainsi que la croissance instantanée de
     * leur clé — la proximité (mesurée) ne se tait jamais.
     */
    fun update(
        lightGreen: Boolean,
        detections: List<Detection>,
        focalPx: Float,
        atMs: Long = -1L,
        motion: FrameMotion? = null,
        depthByBox: Map<List<Float>, Float>? = null, // PISTE2 : proximités par boîte vers le suivi — null = repli géométrie.
    ): TraverseDecision {
        val stopped = stoppedKeys(detections, focalPx, atMs)
        val hushed = if (atMs < 0L) emptySet() else approach.cameraMovedKeys
        val blocking = findBlocking(detections, focalPx, stopped + hushed)
        previousAreas = vehicleAreas(detections)
        previousStill = stillMemoryOf(detections, focalPx, atMs)
        val tracked = approachVerdict(detections, atMs, motion, depthByBox)
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
    private fun approachVerdict(detections: List<Detection>, atMs: Long, motion: FrameMotion?, depthByBox: Map<List<Float>, Float>?): ApproachVerdict {
        if (atMs < 0L) return ApproachVerdict(false, null, null)
        return approach.update(detections, atMs, motion, depthByBox)
    }

    /** Le véhicule bloquant le plus proche, `null` sans veto. */
    private fun findBlocking(detections: List<Detection>, focalPx: Float, stopped: Set<String>): Blocking? =
        detections.mapNotNull { vetoCandidate(it, focalPx, stopped) }
            .minByOrNull { it.meters ?: Float.MAX_VALUE }

    /**
     * Candidature d'une détection au veto : véhicule confiant qui approche ou proche.
     *
     * @param stopped ensemble des identifiants de piste neutralisés (piste à l'arrêt / piste
     *                « hushed »), écartés de la sélection du veto.
     */
    private fun vetoCandidate(detection: Detection, focalPx: Float, stopped: Set<String>): Blocking? {
        if (!VehicleConcepts.isVehicle(detection.label)) return null
        if (detection.score <= SCORE_MIN) return null
        val area = Box.fromProto(detection.box)?.area
        val previous = previousAreas[vehicleKey(detection.label)]
        val growing = area != null && previous != null && area > previous
        val approaching = growing && vehicleKey(detection.label) !in stopped
        if (!approaching && vehicleKey(detection.label) in stopped) return null
        val meters = distanceOf(detection, focalPx)
        val near = meters != null && meters < VETO_METERS
        if (!approaching && !near) return null
        return Blocking(detection.label, meters)
    }

    /**
     * Clés des véhicules confirmés à l'arrêt : mesurés au sténopé sous
     * [VehicleStateWatcher.STOPPED_METERS], dans le couloir marché, quasi
     * immobiles et à seau-mètre stable depuis la frame précédente datée
     * (miroir de la voix [VehicleStateWatcher], au sténopé près — iSee et
     * direct en profitent sans changer leurs signatures). Sans date
     * ([atMs] < 0) : vide, le doute bloque.
     */
    private fun stoppedKeys(detections: List<Detection>, focalPx: Float, atMs: Long): Set<String> {
        if (atMs < 0L || focalPx <= 0f) return emptySet()
        return detections.mapNotNull { detection ->
            if (!VehicleConcepts.isVehicle(detection.label)) return@mapNotNull null
            if (detection.score <= SCORE_MIN) return@mapNotNull null
            val box = Box.fromProto(detection.box) ?: return@mapNotNull null
            if (!box.isSane) return@mapNotNull null
            val key = vehicleKey(detection.label)
            val prev = previousStill[key] ?: return@mapNotNull null
            if (prev.atMs < 0L) return@mapNotNull null
            val dtSeconds = (atMs - prev.atMs) / 1000f
            if (dtSeconds <= 0f || dtSeconds > STILL_STALE_SECONDS) return@mapNotNull null
            val meters = distanceOf(detection, focalPx) ?: return@mapNotNull null
            if (meters > VehicleStateWatcher.STOPPED_METERS) return@mapNotNull null
            val center = detection.normalizedBox() ?: return@mapNotNull null
            val cx = (center[0] + center[2]) / 2f
            val cy = (center[1] + center[3]) / 2f
            if (cx < PositionText.SIDE_EDGE || cx > 1f - PositionText.SIDE_EDGE) return@mapNotNull null
            val px = prev.cx ?: return@mapNotNull null
            val py = prev.cy ?: return@mapNotNull null
            if (hypot(cx - px, cy - py) / dtSeconds > VehicleStateWatcher.STILL_SPEED) return@mapNotNull null
            if (PositionText.sceneBucket(meters) != prev.bucket) return@mapNotNull null
            key
        }.toSet()
    }

    /** Mémoire d'immobilité de la frame (boîte max par label, miroir du veto). */
    private fun stillMemoryOf(detections: List<Detection>, focalPx: Float, atMs: Long): Map<String, StillMemory> =
        detections.mapNotNull { detection ->
            if (!VehicleConcepts.isVehicle(detection.label)) return@mapNotNull null
            if (detection.score <= SCORE_MIN) return@mapNotNull null
            val box = Box.fromProto(detection.box) ?: return@mapNotNull null
            if (!box.isSane) return@mapNotNull null
            vehicleKey(detection.label) to detection
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, same) ->
                val seen = same.maxBy { Box.fromProto(it.box)?.area ?: -1f }
                val center = seen.normalizedBox()
                StillMemory(
                    cx = center?.let { (it[0] + it[2]) / 2f },
                    cy = center?.let { (it[1] + it[3]) / 2f },
                    bucket = PositionText.sceneBucket(distanceOf(seen, focalPx)),
                    atMs = atMs,
                )
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

    /**
     * Un objet calibré proche (véhicule ou non) : le guidage obstacles reste
     * actif. Une personne non mesurable compte comme proche (prudence :
     * l'inconnu ne coupe jamais le guidage devant un humain).
     */
    private fun hasNearObject(detections: List<Detection>, focalPx: Float): Boolean =
        detections.any { detection ->
            if (detection.score <= SCORE_MIN) return@any false
            val meters = distanceOf(detection, focalPx)
            meters?.let { it < VETO_METERS } == true ||
                (meters == null && PersonLabels.isPerson(detection.label))
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
    private fun vehicleKey(label: String): String = VehicleConcepts.keyOf(label)

    /** Immobilité mémorisée : centre normalisé, seau-mètre et date de la frame. */
    private data class StillMemory(
        val cx: Float?,
        val cy: Float?,
        val bucket: String,
        val atMs: Long,
    )

    /** Un véhicule qui bloque : son label et sa distance si elle est mesurable. */
    private data class Blocking(val label: String, val meters: Float?)

    companion object {
        /** Score de confiance minimal d'un véhicule pris au sérieux (strictement dépassé). */
        const val SCORE_MIN = 0.5f

        /** Sous cette distance sténopé (m), un véhicule bloque même sans approche visible. */
        const val VETO_METERS = 15f

        /** Fraîcheur exigée de la frame précédente pour conclure à l'arrêt (s). */
        const val STILL_STALE_SECONDS = 1.0f
    }
}
