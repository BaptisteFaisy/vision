package dev.sam3vision.core.traverse

import dev.sam3vision.core.danger.ApproachThresholds
import dev.sam3vision.core.depth.DepthConfirm
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.predict.BoxPredictor
import dev.sam3vision.core.predict.EgoCompensator // PISTE1
import dev.sam3vision.core.predict.MotionModel
import kotlin.math.abs

/**
 * Verdict d'approche : un véhicule fonce-t-il ([label] du plus dangereux) et
 * l'impact est-il dans [ttcSeconds] secondes (`null` sans approche) ?
 * [lateralMoving] vrai quand un véhicule traverse le champ à distance
 * (quasi) constante ([lateralLabel] le plus rapide) — le danger n° 1 d'un
 * piéton sur un passage, invisible au tau (taille stable). Le veto ne suit
 * que l'approche ; le scan traversée et les annonces d'état suivent les deux.
 */
data class ApproachVerdict(
    val approaching: Boolean,
    val ttcSeconds: Float?,
    val label: String?,
    val lateralMoving: Boolean = false,
    val lateralLabel: String? = null,
)

/**
 * Approche véhicules sur boîtes SAM : le direct `/live` ne donnant pas d'ids
 * stables, l'association se fait ici par IoU entre frames, puis chaque piste
 * porte un [MotionModel] réutilisé (vitesse de largeur lissée) dont on tire
 * le temps-avant-collision tau = largeur / vitesse (Lee 1976).
 *
 * Seuils = priors à recaler sur téléphone ([TTC_SECONDS] & co), direction
 * sûre : une piste jeune, floue ou perdue ne déclenche jamais — seul un
 * rapprochement franc et confirmé force le veto. Pur Kotlin testé en JVM.
 */
class VehicleApproach {

    private class Track(
        var label: String,
        val motion: MotionModel,
        var lastSeenMs: Long,
        // PISTE2 : proximités première/dernière vues — la profondeur confirme l'expansion.
        var firstCloseness: Float? = null,
        var lastCloseness: Float? = null,
    )

    // PISTE2 : [key] = boîte proto, clé des proximités ([DepthConfirm]).
    private data class VehicleBox(val label: String, val box: Box, val key: List<Float>)

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 0

    /** Pistes en cours (après purge) — exposé pour les tests. */
    val trackCount: Int get() = tracks.size

    /** Verdicts fond de la dernière frame par piste (vide sans signal fond). */
    var lastVerdicts: Map<Int, MovingVerdict> = emptyMap()
        private set

    /**
     * Vrai quand chaque piste évaluée accuse la caméra ([Verdict.CAMERA_MOVED])
     * : le porteur bouge, aucun véhicule ne roule — le veto mouvement se tait.
     * Faux sans piste évaluée : on ne met jamais le veto à l'aveugle.
     */
    var cameraMoved: Boolean = false
        private set

    /**
     * Clés véhicules ([VehicleConcepts.keyOf]) dont chaque piste évaluée accuse
     * la caméra : leurs croissances se taisent. Une clé sans piste évaluée
     * (piste jeune) n'y figure jamais — on ne tait que ce qu'on a jugé.
     */
    var cameraMovedKeys: Set<String> = emptySet()
        private set

    /**
     * Fond prouvé franc à la dernière frame ([VehicleBackground.groundOf] au-delà
     * de [VehicleBackground.LATERAL_RATIO]) : la caméra bouge, même si aucune piste
     * ne la suit (boîte fixe sous pan — FUSION : coupe l'arrêt, jamais le mouvement).
     */
    var fondFranc: Boolean = false
        private set

    /** Dernier mouvement fond reçu (`null` = pas de signal) : la décote zoom. */ // PISTE1
    var lastGlobal: MotionSample? = null // PISTE1
        private set

    /** Délai inter-frames du dernier signal fond (ms, 0 sans signal). */ // PISTE1
    var lastGlobalDtMs: Long = 0L // PISTE1
        private set

    /** Oublie toutes les pistes (nouvelle traversée). */
    fun reset() {
        tracks.clear()
        nextId = 0
        lastVerdicts = emptyMap()
        cameraMoved = false
        cameraMovedKeys = emptySet()
        fondFranc = false // FUSION
        lastGlobal = null // PISTE1
        lastGlobalDtMs = 0L // PISTE1
    }

    /**
     * Verdict pour une frame datée de [atMs] (ms, monotone). [motion]
     * (`null` = pas de signal fond) fait juger chaque piste par
     * [VehicleBackground] : une piste qui suit le défilement du fond
     * ([Verdict.CAMERA_MOVED]) ne conclut plus l'approche — les pistes
     * restent suivies, seul le verdict se tait. [depthByBox] (PISTE2,
     * `null` = pas de profondeur : repli géométrie) invalide les pistes
     * dont la proximité ne suit pas l'expansion (pan/zoom caméra).
     */
    fun update(
        detections: List<Detection>,
        atMs: Long,
        motion: FrameMotion? = null,
        depthByBox: Map<List<Float>, Float>? = null, // PISTE2
    ): ApproachVerdict {
        val frameWidth = detections.maxOfOrNull { it.width }?.takeIf { it > 0 } ?: FALLBACK_WIDTH
        val frameHeight = detections.maxOfOrNull { it.height }?.takeIf { it > 0 } ?: FALLBACK_HEIGHT
        associate(vehiclesOf(detections), atMs, frameWidth, depthByBox)
        dropStale(atMs)
        evaluateMotion(motion, frameWidth, frameHeight)
        return verdict(frameWidth)
    }

    /**
     * Verdicts fond des pistes à vitesse établie : la vitesse lissée de la
     * piste (px/s) passe en fractions d'image par frame pour [VehicleBackground].
     * Sans [motion], verdicts vides et [cameraMoved] faux (comportement inchangé).
     */
    private fun evaluateMotion(motion: FrameMotion?, frameWidth: Int, frameHeight: Int) {
        if (motion == null) {
            lastVerdicts = emptyMap()
            cameraMoved = false
            cameraMovedKeys = emptySet()
            fondFranc = false // FUSION
            lastGlobal = null // PISTE1
            lastGlobalDtMs = 0L // PISTE1
            return
        }
        fondFranc = VehicleBackground.groundOf( // FUSION : arrêt coupé sous fond franc, sans piste suiveuse.
            motion.global, motion.lumaWidth, motion.lumaHeight, motion.dtMs,
        )?.let { it > VehicleBackground.LATERAL_RATIO } == true
        lastGlobal = motion.global // PISTE1
        lastGlobalDtMs = motion.dtMs // PISTE1
        val dtSeconds = motion.dtMs / 1000f
        // PISTE3+FUSION : corroborer ici par SamResidu.decide (résidu masque compensé ego-motion,
        // verdict via ResiduDecision.toVerdict — null = doute, garder le verdict V1) dès que les
        // pistes retiendront le masque SAM de l'objet (prérequis : décodage + association inter-frames).
        lastVerdicts = tracks.mapNotNull { (id, track) ->
            if (!track.motion.velocityReliable) return@mapNotNull null
            val boxDx = track.motion.velocity.x / frameWidth * dtSeconds
            val boxDy = track.motion.velocity.y / frameHeight * dtSeconds
            id to VehicleBackground.decide(id, boxDx, boxDy, motion.global, motion.lumaWidth, motion.lumaHeight, motion.dtMs, 0f)
        }.toMap()
        cameraMoved = lastVerdicts.isNotEmpty() && lastVerdicts.all { it.value.verdict == Verdict.CAMERA_MOVED }
        cameraMovedKeys = lastVerdicts.entries
            .groupBy { VehicleConcepts.keyOf(tracks.getValue(it.key).label) }
            .filterValues { group -> group.all { it.value.verdict == Verdict.CAMERA_MOVED } }
            .keys
    }

    /** Véhicules confiants à boîte saine, seuls suivis. */
    private fun vehiclesOf(detections: List<Detection>): List<VehicleBox> =
        detections.mapNotNull { detection ->
            if (!VehicleConcepts.isVehicle(detection.label)) return@mapNotNull null
            if (detection.score <= TraverseVeto.SCORE_MIN) return@mapNotNull null
            val box = Box.fromProto(detection.box) ?: return@mapNotNull null
            if (!box.isSane) return@mapNotNull null
            VehicleBox(detection.label, box, detection.box)
        }

    /** Associe chaque véhicule à sa piste (meilleur IoU) ou en crée une. */
    private fun associate(vehicles: List<VehicleBox>, atMs: Long, frameWidth: Int, depthByBox: Map<List<Float>, Float>?) {
        val maxSpeed = frameWidth * BoxPredictor.DEFAULT_MAX_SPEED_RATIO
        val claimed = HashSet<Int>()
        for (vehicle in vehicles) {
            val id = bestTrack(vehicle.box, atMs, claimed)
            if (id == null) {
                val closeness = closenessOf(depthByBox, vehicle.key) // PISTE2
                tracks[nextId] = Track(vehicle.label, MotionModel(vehicle.box, atMs), atMs, closeness, closeness)
                nextId += 1
            } else {
                claimed += id
                val track = tracks.getValue(id)
                track.label = vehicle.label
                track.motion.observe(vehicle.box, atMs, maxSpeed)
                track.lastSeenMs = atMs
                // PISTE2 : la dernière proximité suit la boîte (sans mesure, on garde l'ancienne).
                closenessOf(depthByBox, vehicle.key)?.let { track.lastCloseness = it }
            }
        }
    }

    /** Proximité finie de la boîte [key], `null` sans mesure (PISTE2). */
    private fun closenessOf(depthByBox: Map<List<Float>, Float>?, key: List<Float>): Float? =
        depthByBox?.get(key)?.takeIf { it.isFinite() }

    /** Piste prédite la plus proche, `null` sous le seuil IoU ou déjà prise. */
    private fun bestTrack(box: Box, atMs: Long, claimed: Set<Int>): Int? {
        var bestId: Int? = null
        var bestIou = 0f
        for ((id, track) in tracks) {
            if (id in claimed) continue
            val iou = box.iou(track.motion.predictAt(atMs))
            if (iou > bestIou) {
                bestIou = iou
                bestId = id
            }
        }
        if (bestIou < IOU_MIN) return null
        return bestId
    }

    /** Oublie les pistes sans observation récente (objet sorti ou perdu). */
    private fun dropStale(atMs: Long) {
        val stale = tracks.filterValues { atMs - it.lastSeenMs > STALE_MS }.keys
        stale.forEach { tracks.remove(it) }
    }

    /** Le plus dangereux des rapprochements francs, silence sinon. */
    private fun verdict(frameWidth: Int): ApproachVerdict {
        val approach = approachOf(frameWidth)
        val lateral = lateralOf(frameWidth)
        return ApproachVerdict(approach.first, approach.second, approach.third, lateral.first, lateral.second)
    }

    /** Rapprochement franc : plus petit tau sous [TTC_SECONDS] (`false` sinon). */
    private fun approachOf(frameWidth: Int): Triple<Boolean, Float?, String?> {
        var bestLabel: String? = null
        var bestTtc = Float.MAX_VALUE
        for ((id, track) in tracks) {
            if (!track.motion.velocityReliable) continue
            if (lastVerdicts[id]?.verdict == Verdict.CAMERA_MOVED) continue
            // PISTE2 : expansion à profondeur constante = pan/zoom caméra, pas une approche.
            if (DepthConfirm.contradicts(track.firstCloseness, track.lastCloseness)) continue
            val width = track.motion.anchorBox.width
            if (width < frameWidth * MIN_WIDTH_FRACTION) continue
            if (track.motion.velocity.width <= frameWidth * MIN_GROWTH_RATIO) continue
            // PISTE1 : croissance expliquée par le zoom du fond — pas une approche.
            if (EgoCompensator.discountGrowth(track.motion.velocity.width / width, lastGlobal, lastGlobalDtMs).discounted) continue
            val tau = width / track.motion.velocity.width
            if (tau < bestTtc) {
                bestTtc = tau
                bestLabel = track.label
            }
        }
        if (bestLabel == null || bestTtc >= TTC_SECONDS) return Triple(false, null, null)
        return Triple(true, bestTtc, bestLabel)
    }

    /**
     * Mouvement latéral franc : le plus rapide au-delà de [LATERAL_RATIO].
     * Comme l'approche, une piste embarquée par la caméra
     * ([Verdict.CAMERA_MOVED]) ne conclut pas — un panoramique n'est pas
     * une voiture qui roule.
     */
    private fun lateralOf(frameWidth: Int): Pair<Boolean, String?> {
        var bestLabel: String? = null
        var bestSpeed = 0f
        for ((id, track) in tracks) {
            if (!track.motion.velocityReliable) continue
            if (lastVerdicts[id]?.verdict == Verdict.CAMERA_MOVED) continue
            if (track.motion.anchorBox.width < frameWidth * MIN_WIDTH_FRACTION) continue
            val speed = abs(track.motion.velocity.x)
            if (speed <= frameWidth * LATERAL_RATIO) continue
            if (speed > bestSpeed) {
                bestSpeed = speed
                bestLabel = track.label
            }
        }
        return (bestLabel != null) to bestLabel
    }

    companion object {
        /** Sous ce temps-avant-collision (s), le véhicule force le veto. */
        const val TTC_SECONDS = 3.0f

        /** IoU minimal pour rattacher une boîte à une piste. */
        const val IOU_MIN = 0.3f

        /** Au-delà sans observation, la piste est oubliée. */
        const val STALE_MS = 1000L

        /** Largeur minimale d'une boîte prise au sérieux (fraction d'image). */
        const val MIN_WIDTH_FRACTION = 0.02f

        /** Croissance minimale (fraction d'image/s) : en dessous, c'est du bruit. */
        const val MIN_GROWTH_RATIO = 0.005f

        /**
         * Vitesse latérale minimale (fraction d'image/s) : une voiture qui
         * traverse le champ à 50 km/h à 20 m fait ~2/3 d'image par seconde,
         * le tremblé de boîte lissée reste sous ~2 %/s — 8 % sépare les deux
         * (prior à valider sur téléphone, comme les autres seuils).
         */
        const val LATERAL_RATIO = 0.08f

        /** Largeur d'image de repli quand les détections ne la donnent pas. */
        const val FALLBACK_WIDTH = 1000

        /** Hauteur d'image de repli (même rôle pour la dérive verticale). */
        const val FALLBACK_HEIGHT = 1000
    }
}

/**
 * Seuils d'expansion des véhicules pour
 * [dev.sam3vision.core.danger.ApproachEstimator] (PISTE2) : un véhicule est
 * un danger grave — seuil de grossissement abaissé (0,02), couloir élargi
 * (0,30, un véhicule large menace même excentré), TTC plafonné à 4 s.
 * Priors à recaler sur téléphone, comme [VehicleApproach.TTC_SECONDS].
 */
val VEHICLE_APPROACH_THRESHOLDS = ApproachThresholds(
    minGrowthPerSec = 0.02f,
    centerMargin = 0.30f,
    maxTtcSec = 4f,
)

/**
 * Seuils d'expansion par label pour
 * [dev.sam3vision.core.danger.ApproachEstimator] (PISTE2) : [vehicle] pour
 * les labels de [VehicleConcepts], [person] pour tout le reste — à passer
 * en `thresholdsForLabel` (`ApproachEstimator(thresholdsForLabel =
 * ::approachThresholdsFor)`).
 */
fun approachThresholdsFor(
    label: String,
    vehicle: ApproachThresholds = VEHICLE_APPROACH_THRESHOLDS,
    person: ApproachThresholds = ApproachThresholds(),
): ApproachThresholds = if (VehicleConcepts.isVehicle(label)) vehicle else person
