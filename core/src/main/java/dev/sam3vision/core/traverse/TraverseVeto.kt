package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Concepts véhicules du veto trafic : ce que SAM doit suivre en mode traversée.
 *
 * Les cinq véhicules du contrat (audit réel 31/10 : camion et moto réintégrés,
 * trottinettes toujours sorties), en anglais (la forme que SAM segmente le mieux).
 * Le français reste reconnu côté veto ([isVehicle]) : un label « voiture » bloque
 * comme un « car », « trottinette » comme un « scooter ». Le van (« van »,
 * prompt de la base dehors depuis le 03/11) bloque comme une voiture,
 * « camionnette » et « fourgon » comme lui (miroir de `concepts.py` serveur).
 */
object VehicleConcepts {

    /** Les cinq véhicules du contrat : voiture, bus, vélo, camion et moto (audit réel 31/10). */
    val PROMPTS: List<String> = listOf("car", "bus", "bicycle", "truck", "motorcycle")

    private val KEYS = setOf(
        "car", "bus", "buses", "truck", "motorcycle", "bicycle", "bike",
        "van", "vans", "scooter", "electric scooter", "trottinette", "trottinette electrique",
        "voiture", "camion", "camionnette", "fourgon", "moto", "velo",
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
     * [LivePrompts.MAX] (22). La base est filtrée en amont (poubelle,
     * souvenirs, lampadaire écartés — ordre user 03/11) : 20 envoyés, chien
     * conservé.
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
    /** Vrai quand un véhicule force l'attente (hors vert : le vert gagne toujours). */
    val veto: Boolean,
    /** Libellé du véhicule bloquant, `null` sans veto. */
    val blockingLabel: String?,
    /** Distance sténopé du bloquant (m), `null` si non mesurable. */
    val blockingMeters: Float?,
    /** Temps-avant-collision du plus dangereux (`null` sans approche suivie). */
    val approachTtcSeconds: Float? = null,
    /**
     * Vrai quand un objet proche (véhicule ou non) est mesuré, ou qu'un véhicule confiant
     * est non mesuré : le guidage obstacles reste actif malgré le mode traversée — on ne coupe
     * jamais tout le guidage devant un danger possible, l'exclusion est levée.
     */
    val keepObstacleGuidance: Boolean,
)

/**
 * Veto trafic de la traversée, actif hors vert : sans vert confirmé, un
 * véhicule qui approche ou qui est proche force « attendez ». Sur vert
 * confirmé, le vert gagne toujours (ordre user du 02/11) — le suivi
 * continue (instantané + pistes tenues chaudes) pour trancher dès le flip
 * rouge, et le guidage obstacles reste actif devant un objet proche.
 *
 * Un véhicule bloque (hors vert) quand son score dépasse [SCORE_MIN] **et**
 * qu'il approche
 * (croissance franche de boîte contre sa mémoire appariée, au-delà du tremblé
 * [AREA_JITTER_RATIO]) **ou** qu'il est proche (distance sténopé sous
 * [VETO_METERS], confirmée quand le score est faible : même instance proche à
 * la frame précédente, anti-flicker [PROXIMITY_STRONG_SCORE]). L'approche se lit sur les boîtes seules — sans focale, elle
 * reste le seul critère ; la distance réutilise [KnownSizes] (déjà calibré
 * pour les sept véhicules) et [Pinhole]. Les vus se suivent par instance
 * (label + seau L/C/R, appariement IoU) : deux garées sous le même label ne
 * fusionnent plus, une bascule de max ne fait plus une approche fantôme. Un
 * véhicule prouvé immobile
 * (mesuré sous [STILL_EXEMPT_METERS], centre quasi fixe, portée stable à
 * [STILL_RANGE_M] près, croissance sous le tremblé [AREA_JITTER_RATIO], fond
 * fixe — voir [stoppedKeys]) ne bloque plus :
 * le vert seul décide alors, à toute place dans l'image (une garée sur le
 * côté n'interdit pas la traversée, ordre user du 01/11) ; en mouvement,
 * proche non prouvé immobile ou doute bloque — sauf croissance expliquée par
 * la caméra (pan suivi, voir [vetoCandidate]). L'approche suivie
 * (IoU + tau < 3 s — voir [approachVerdict]) bloque aussi, en ceinture et
 * bretelles. Au-dessus de tout ça, ce que [ParkedProof] prouve garé sur sa
 * fenêtre sort du veto : le test paire-de-frames ci-dessus reste sous le bruit
 * d'une prise à la main, seul il ne prouve qu'une caméra fixe.
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

    /** Véhicules vus à la frame précédente, par instance (clé : label + seau L/C/R, voir [instanceKey]). */
    private var previousSeen: Map<String, List<StillMemory>> = emptyMap()

    /** Suivi temporel des véhicules (IoU + TTC) — ceinture et bretelles du veto. */
    private val approach = VehicleApproach()

    /** Preuve « garée » sur fenêtre : ce qu'elle prouve sort du veto (voir [ParkedProof]). */
    private val parked = ParkedProof()

    /** Mouvement de la caméra lu sur les objets fixes (voir [AnchorMotion]). */
    private val anchors = AnchorMotion()

    /** Pas de caméra de la frame courante, `null` hors balayage prouvé. */
    private var sweepStep: CameraStep? = null

    /** Balayage prouvé à la dernière frame, `null` caméra posée ou doute : le verdict juge sur le même signal. */
    val cameraMotion: FrameMotion? get() = anchors.motion

    /** Vrai si [detection] (dernière frame) est un véhicule prouvé garé : le verdict l'écarte aussi. */
    fun isParked(detection: Detection): Boolean = parked.isParked(detection)

    /** Oublie la phase, la frame précédente et les pistes (nouvelle traversée). */
    @Synchronized
    fun reset() {
        currentState = TraversePhase.ATTENDRE
        previousSeen = emptyMap()
        approach.reset()
        parked.reset()
        anchors.reset()
    }

    /**
     * Décision pour une frame. [lightGreen] dit si le vert est confirmé
     * (sur vert, pas de veto : le vert gagne toujours, suivi tenu chaud),
     * [detections] porte les boîtes SAM, [focalPx] la focale en pixels de l'image
     * analysée (≤ 0 : distance indisponible, seule l'approche compte), [atMs]
     * la date de la frame (< 0 : suivi temporel désactivé, veto instantané seul).
     * [motion] (`null` = pas de signal fond) fait taire l'approche suivie des
     * pistes qui suivent la caméra, ainsi que la croissance instantanée de
     * leur clé — le guidage obstacles, lui, reste actif devant un objet proche.
     * [noLight] vrai (absence de feu confirmée par l'appelant) vaut vert pour
     * la phase, mais le veto reste armé : sans feu, ce sont les véhicules
     * qui tranchent — un passage sans feu ni voiture dit « traversez ».
     */
    @Synchronized
    fun update(
        lightGreen: Boolean,
        detections: List<Detection>,
        focalPx: Float,
        atMs: Long = -1L,
        motion: FrameMotion? = null,
        depthByBox: Map<List<Float>, Float>? = null, // PISTE2 : proximités par boîte vers le suivi — null = repli géométrie.
        noLight: Boolean = false,
    ): TraverseDecision {
        val camera = anchors.update(detections, atMs)
        val ground = anchors.motion ?: motion
        sweepStep = camera.takeIf { anchors.motion != null }
        parked.update(detections, atMs, camera == null && groundOf(motion)?.let { it > ParkedProof.PAN_SPEED } == true, camera)
        val live = detections.filterNot(parked::isParked)
        val picked = seenByInstance(live)
        val stopped = stoppedKeys(picked, focalPx, atMs, ground)
        val hushed = if (atMs < 0L) emptySet() else approach.cameraMovedKeys
        val blocking = findBlocking(picked, focalPx, stopped, hushed)
        previousSeen = stillMemoryOf(picked, focalPx, atMs)
        val tracked = approachVerdict(live, atMs, ground, depthByBox)
        // Vert confirmé : le vert gagne toujours (ordre user du 02/11). Le
        // suivi (instantané + pistes) tourne quand même, tenu chaud pour
        // trancher dès le flip rouge ; seule la sortie veto est tue.
        // Sous balayage prouvé, une piste qui n'est plus nourrie est sortie du cadre
        // ou prouvée garée : son tau ne tient plus le veto, caméra posée rien ne change.
        val approaching = tracked.approaching && (tracked.tauFresh || anchors.motion == null)
        val veto = (blocking != null || approaching) && !lightGreen
        currentState = nextState(lightGreen || noLight, veto)
        return TraverseDecision(
            state = currentState,
            cue = cueOf(currentState),
            veto = veto,
            blockingLabel = blocking?.label.takeIf { veto },
            blockingMeters = blocking?.meters.takeIf { veto },
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
    private fun findBlocking(
        picked: Map<String, List<Seen>>,
        focalPx: Float,
        stopped: Set<Seen>,
        hushed: Set<String>,
    ): Blocking? =
        picked.values.flatten().mapNotNull { vetoCandidate(it, focalPx, stopped, hushed) }
            .minByOrNull { it.meters ?: Float.MAX_VALUE }

    /**
     * Candidature d'un véhicule vu au veto : croissance franche de boîte
     * ([isGrowingBeyondJitter] : le tremblé SAM ≤ 4 % n'est pas une approche),
     * ou proximité mesurée sous [VETO_METERS]. La croissance se lit contre la
     * mémoire appariée ([matchOf]) — à défaut (jeune sans recouvrement),
     * contre la surface max de la clé (repli fail-closed, miroir de l'ancienne
     * fusion).
     *
     * @param stopped vus prouvés immobiles ([stoppedKeys]), écartés un par un
     * (une garée n'exempte pas la voiture qui roule à côté).
     * @param hushed clés d'instance tues par le suivi caméra
     * (`VehicleApproach.cameraMovedKeys`) — les deux granularités (label,
     * instance) sont honorées.
     */
    private fun vetoCandidate(seen: Seen, focalPx: Float, stopped: Set<Seen>, hushed: Set<String>): Blocking? {
        if (seen in stopped) return null
        if (seen.key in hushed || vehicleKey(seen.detection.label) in hushed) return null
        val previous = matchOf(seen)?.area ?: fallbackArea(seen.key)
        val growing = previous != null && isGrowingBeyondJitter(previous, seen.box.area) && !isEnteringOnSweep(seen)
        val meters = distanceOf(seen.detection, focalPx)
        val near = meters != null && meters < VETO_METERS && nearConfirmed(seen)
        if (!growing && !near) return null
        return Blocking(seen.detection.label, meters)
    }

    /**
     * Sous balayage prouvé, une boîte que le cadre coupe grandit parce qu'elle
     * rentre : son bord libre suit la caméra. Une voiture qui roule et entre va
     * plus vite que le balayage, sa croissance reste une croissance.
     */
    private fun isEnteringOnSweep(seen: Seen): Boolean {
        val step = sweepStep ?: return false
        val before = matchOf(seen)?.box ?: return false
        val box = seen.detection.box
        val width = seen.detection.width.toFloat()
        val moved = when {
            box[0] <= EDGE_PX -> box[2] - before.x2
            box[2] >= width - EDGE_PX -> box[0] - before.x1
            else -> return false
        }
        return abs(moved / width - step.dx) <= ENTRY_TOLERANCE
    }

    /**
     * Proximité confirmée : score franc (≥ [PROXIMITY_STRONG_SCORE]) ou même
     * instance proche à la frame précédente (mémoire [previousSeen], IoU ≥
     * [CONTINUITY_IOU_MIN]) — un flicker SAM (apparition isolée, score faible
     * de nuit, fragments qui alternent) ne bloque pas ; une voiture vraiment
     * proche confirme dès la 2e frame. La croissance franche (approche réelle)
     * bloque, elle, sans confirmation.
     */
    private fun nearConfirmed(seen: Seen): Boolean {
        if (seen.detection.score >= PROXIMITY_STRONG_SCORE) return true
        val memories = previousSeen[seen.key] ?: return false
        return memories.any { it.meters != null && it.meters < VETO_METERS && it.box.iou(seen.box) >= CONTINUITY_IOU_MIN }
    }

    /**
     * Vus prouvés immobiles : mesurés au sténopé sous [STILL_EXEMPT_METERS],
     * quasi immobiles et à portée stable ([isStableRange] : ±[STILL_RANGE_M],
     * pas d'égalité de seau — une frontière ne casse pas l'arrêt) depuis la
     * mémoire appariée ([matchOf]) de la frame précédente datée, boîte qui ne
     * grandit pas au-delà du tremblé ([isGrowingBeyondJitter] : une approche
     * de face garde son centre et peut garder sa portée — la croissance
     * franche tranche), fond fixe (un pan prouvé refuse toute exemption : une
     * boîte statique dans l'image bouge alors avec la caméra). Sans date
     * ([atMs] < 0) : vide, le doute bloque. Plus de couloir (01/11) : une
     * garée sur le côté, prouvée immobile, n'interdit pas la traversée.
     */
    private fun stoppedKeys(
        picked: Map<String, List<Seen>>,
        focalPx: Float,
        atMs: Long,
        motion: FrameMotion?,
    ): Set<Seen> {
        if (atMs < 0L || focalPx <= 0f || backgroundPanning(motion)) return emptySet()
        return picked.values.flatten().mapNotNull { seen ->
            val prev = matchOf(seen) ?: return@mapNotNull null
            if (prev.atMs < 0L) return@mapNotNull null
            val dtSeconds = (atMs - prev.atMs) / 1000f
            if (dtSeconds <= 0f || dtSeconds > STILL_STALE_SECONDS) return@mapNotNull null
            val meters = distanceOf(seen.detection, focalPx) ?: return@mapNotNull null
            if (meters > STILL_EXEMPT_METERS) return@mapNotNull null
            if (isGrowingBeyondJitter(prev.area, seen.box.area)) return@mapNotNull null
            val center = seen.detection.normalizedBox() ?: return@mapNotNull null
            val cx = (center[0] + center[2]) / 2f
            val cy = (center[1] + center[3]) / 2f
            val px = prev.cx ?: return@mapNotNull null
            val py = prev.cy ?: return@mapNotNull null
            if (hypot(cx - px, cy - py) / dtSeconds > VehicleStateWatcher.STILL_SPEED) return@mapNotNull null
            if (!isStableRange(meters, prev.meters)) return@mapNotNull null
            seen
        }.toSet()
    }

    /**
     * Fond prouvé en pan (vitesse franche au-delà de
     * [VehicleBackground.LATERAL_RATIO]) : une boîte statique dans l'image
     * bouge alors avec la caméra, ce n'est pas un arrêt prouvé. Fond inconnu
     * (`null` ou inutilisable) : on ne prouve pas le pan, l'immobilité mesurée
     * vaut arrêt (le chemin iSee ne câble pas le fond au veto).
     */
    private fun backgroundPanning(motion: FrameMotion?): Boolean =
        groundOf(motion)?.let { it > VehicleBackground.LATERAL_RATIO } == true

    /** Vitesse du fond (image/s), `null` sans signal utilisable. */
    private fun groundOf(motion: FrameMotion?): Float? =
        motion?.let { VehicleBackground.groundOf(it.global, it.lumaWidth, it.lumaHeight, it.dtMs) }

    /** Véhicules confiants à boîte saine, groupés par instance (deux garées à deux endroits ne fusionnent plus). */
    private fun seenByInstance(detections: List<Detection>): Map<String, List<Seen>> {
        val picked = LinkedHashMap<String, MutableList<Seen>>()
        for (detection in detections) {
            if (!VehicleConcepts.isVehicle(detection.label)) continue
            if (detection.score <= SCORE_MIN) continue
            val box = Box.fromProto(detection.box) ?: continue
            if (!box.isSane) continue
            val key = instanceKey(detection)
            picked.getOrPut(key) { mutableListOf() } += Seen(key, detection, box)
        }
        return picked
    }

    /** Mémoire de la frame : chaque vu retient boîte, surface, centre, portée et date. */
    private fun stillMemoryOf(picked: Map<String, List<Seen>>, focalPx: Float, atMs: Long): Map<String, List<StillMemory>> =
        picked.mapValues { (_, seen) ->
            seen.map { current ->
                val center = current.detection.normalizedBox()
                val meters = distanceOf(current.detection, focalPx)
                StillMemory(
                    box = current.box,
                    area = current.box.area,
                    cx = center?.let { (it[0] + it[2]) / 2f },
                    cy = center?.let { (it[1] + it[3]) / 2f },
                    meters = meters,
                    bucket = PositionText.sceneBucket(meters),
                    atMs = atMs,
                )
            }
        }

    /** Mémoire précédente qui recouvre [seen] (meilleur IoU, [CONTINUITY_IOU_MIN] min) : la même voiture, pas la voisine. */
    private fun matchOf(seen: Seen): StillMemory? {
        val memories = previousSeen[seen.key] ?: return null
        return memories.maxByOrNull { it.box.iou(seen.box) }
            ?.takeIf { it.box.iou(seen.box) >= CONTINUITY_IOU_MIN }
    }

    /** Surface max des mémoires de [key] (`null` sans mémoire) : repli fail-closed du jeune sans recouvrement. */
    private fun fallbackArea(key: String): Float? =
        previousSeen[key]?.maxOfOrNull { it.area }

    /** Clé d'instance d'une détection (label + seau L/C/R, miroir de l'approche). */
    private fun instanceKey(detection: Detection): String {
        val center = detection.normalizedBox()
        val cx = center?.let { (it[0] + it[2]) / 2f } ?: 0.5f
        return VehicleApproach.instanceKeyOf(detection.label, cx)
    }

    /** Distance sténopé d'une détection, `null` si focale ou calibrage manquant. */
    private fun distanceOf(detection: Detection, focalPx: Float): Float? {
        if (focalPx <= 0f) return null
        val realHeight = KnownSizes.heightMeters(detection.label) ?: return null
        return Pinhole.distanceMeters(detection.box, realHeight, focalPx)
    }

    /**
     * Guidage obstacles à garder : objet calibré proche (véhicule ou non), ou véhicule
     * confiant non mesuré (jamais prouvé loin — annoncé sans distance, pas coupé).
     */
    private fun hasNearObject(detections: List<Detection>, focalPx: Float): Boolean =
        detections.any { detection ->
            if (detection.score <= SCORE_MIN) return@any false
            val meters = distanceOf(detection, focalPx)
            if (meters != null) meters < VETO_METERS else VehicleConcepts.isVehicle(detection.label)
        }

    /** Transition : hors vert, le veto prime ; l'alerte exige une reconfirmation. */
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

    /** Véhicule vu de la frame, rattaché à sa clé d'instance. */
    private data class Seen(
        val key: String,
        val detection: Detection,
        val box: Box,
    )

    /** Vu mémorisé : boîte, surface, centre normalisé, portée, seau-mètre et date de la frame. */
    private data class StillMemory(
        val box: Box,
        val area: Float,
        val cx: Float?,
        val cy: Float?,
        val meters: Float?,
        val bucket: String,
        val atMs: Long,
    )

    /** Un véhicule qui bloque : son label et sa distance si elle est mesurable. */
    private data class Blocking(val label: String, val meters: Float?)

    companion object {
        /** Marge du cadre (px) sous laquelle un bord est coupé. */
        private const val EDGE_PX = 1f

        /** Écart toléré entre bord libre et caméra (fraction d'image par frame) : le tremblé de SAM. */
        private const val ENTRY_TOLERANCE = 0.02f

        /** Score de confiance minimal d'un véhicule pris au sérieux (strictement dépassé). */
        const val SCORE_MIN = 0.35f

        /** Sous cette distance sténopé (m), un objet proche garde le guidage obstacles actif. */
        const val VETO_METERS = 15f

        /** Score à partir duquel la proximité bloque dès la première frame ; en dessous, il faut la même instance proche à la frame précédente (anti-flicker nuit). */
        const val PROXIMITY_STRONG_SCORE = 0.5f

        /** Un véhicule prouvé immobile sous cette distance sténopé (m) ne bloque plus : le vert seul décide (01/11). */
        const val STILL_EXEMPT_METERS = 15f

        /** Fraîcheur exigée de la frame précédente pour conclure à l'arrêt (s). */
        const val STILL_STALE_SECONDS = 1.0f

        /** Recouvrement minimal pour rattacher un vu à sa mémoire (miroir du watcher : téléportation interdite). */
        private const val CONTINUITY_IOU_MIN = 0.3f
    }
}
