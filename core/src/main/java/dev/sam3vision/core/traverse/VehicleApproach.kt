package dev.sam3vision.core.traverse

import dev.sam3vision.core.depth.DepthConfirm
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.EgoMotion
import dev.sam3vision.core.motion.GyroMotion
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
 * [lateralTtcSeconds] expose le temps-avant-croisement latéral (largeur /
 * vitesse latérale, `null` sans latéral franc) : informatif seul, il ne
 * déclenche jamais.
 */
data class ApproachVerdict(
    val approaching: Boolean,
    val ttcSeconds: Float?,
    val label: String?,
    val lateralMoving: Boolean = false,
    val lateralLabel: String? = null,
    val lateralTtcSeconds: Float? = null,
)

/**
 * Approche véhicules sur boîtes SAM : le direct `/live` ne donnant pas d'ids
 * stables, l'association se fait ici par IoU entre frames, puis chaque piste
 * porte un [MotionModel] réutilisé (vitesse de largeur lissée) dont on tire
 * le temps-avant-collision tau = largeur / vitesse (Lee 1976), plus le
 * TTC latéral (largeur / vitesse latérale) exposé au veto et la vitesse
 * radiale Kalman consommée en interne (confirmation, jamais suppression).
 * Pistes par instance (clé label + seau de position, voir [instanceKeyOf]),
 * purge à [STALE_MS] avec réactivation courte ([REACTIVATE_MS]).
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

    private data class DroppedTrack(val track: Track, val droppedAtMs: Long)

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 0

    private val dropped = LinkedHashMap<Int, DroppedTrack>()

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
     * Clés d'instance ([instanceKeyOf]) dont chaque piste évaluée accuse
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
        dropped.clear()
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
     * [radialMpsByKey] porte les vitesses radiales Kalman (m/s, négatif en
     * approche) par clé d'instance (`null` = pas de signal distance :
     * comportement inchangé) : une radiale franche confirme l'approche,
     * jamais elle ne supprime un tau. [gyro] (gyro-arret, `null` = verdicts
     * visuels seuls, strictement inchanges) : rotation camera datee dans les
     * unites du plan luma — repli quand le fond optique est muet (pan rapide),
     * ignoree devant un fond sur. Le gyro ne decote jamais la croissance.
     */
    fun update(
        detections: List<Detection>,
        atMs: Long,
        motion: FrameMotion? = null,
        depthByBox: Map<List<Float>, Float>? = null, // PISTE2
        radialMpsByKey: Map<String, Float>? = null,
        gyro: GyroMotion? = null,
    ): ApproachVerdict {
        val frameWidth = detections.maxOfOrNull { it.width }?.takeIf { it > 0 } ?: FALLBACK_WIDTH
        val frameHeight = detections.maxOfOrNull { it.height }?.takeIf { it > 0 } ?: FALLBACK_HEIGHT
        associate(vehiclesOf(detections), atMs, frameWidth, depthByBox)
        dropStale(atMs)
        evaluateMotion(motion, gyro, frameWidth, frameHeight)
        return verdict(frameWidth, frameHeight, radialMpsByKey)
    }

    /** Boîte tronquée par le cadre : sa croissance/vitesse est un artefact de (dé)clip, pas une mesure. */
    private fun onEdge(box: Box, frameWidth: Int, frameHeight: Int): Boolean =
        box.x1 <= EDGE_PX || box.x2 >= frameWidth - EDGE_PX || box.y1 <= EDGE_PX || box.y2 >= frameHeight - EDGE_PX

    /**
     * Verdicts fond des pistes à vitesse établie : la vitesse lissée de la
     * piste (px/s) passe en fractions d'image par frame pour [VehicleBackground].
     * Sans [motion] ni [gyro], verdicts vides et [cameraMoved] faux (comportement inchange).
     */
    private fun evaluateMotion(motion: FrameMotion?, gyro: GyroMotion?, frameWidth: Int, frameHeight: Int) {
        if (motion == null && gyro == null) {
            lastVerdicts = emptyMap()
            cameraMoved = false
            cameraMovedKeys = emptySet()
            fondFranc = false // FUSION
            lastGlobal = null // PISTE1
            lastGlobalDtMs = 0L // PISTE1
            return
        }
        fondFranc = VehicleBackground.groundOf( // FUSION : arrêt coupé sous fond franc, sans piste suiveuse.
            fusedSample(motion, gyro), fusedLumaWidth(motion, gyro), fusedLumaHeight(motion, gyro), fusedDtMs(motion, gyro),
        )?.let { it > VehicleBackground.LATERAL_RATIO } == true
        lastGlobal = motion?.global // PISTE1 (visuel seul : le gyro, rotation pure, ne decote jamais la croissance)
        lastGlobalDtMs = motion?.dtMs ?: 0L // PISTE1
        val dtSeconds = fusedDtMs(motion, gyro) / 1000f
        // PISTE3+FUSION : corroborer ici par SamResidu.decide (résidu masque compensé ego-motion,
        // verdict via ResiduDecision.toVerdict — null = doute, garder le verdict V1) dès que les
        // pistes retiendront le masque SAM de l'objet (prérequis : décodage + association inter-frames).
        lastVerdicts = tracks.mapNotNull { (id, track) ->
            if (!track.motion.velocityReliable) return@mapNotNull null
            val boxDx = track.motion.velocity.x / frameWidth * dtSeconds
            val boxDy = track.motion.velocity.y / frameHeight * dtSeconds
            id to VehicleBackground.decide(id, boxDx, boxDy, motion?.global, fusedLumaWidth(motion, gyro), fusedLumaHeight(motion, gyro), fusedDtMs(motion, gyro), 0f, gyro?.hint)
        }.toMap()
        cameraMoved = lastVerdicts.isNotEmpty() && lastVerdicts.all { it.value.verdict == Verdict.CAMERA_MOVED }
        cameraMovedKeys = lastVerdicts.entries
            .groupBy { entry ->
                val track = tracks.getValue(entry.key)
                instanceKeyOf(track.label, track.motion.anchorBox.centerX / frameWidth)
            }
            .filterValues { group -> group.all { it.value.verdict == Verdict.CAMERA_MOVED } }
            .keys
    }

    /** Echantillon ego-motion fusionne (visuel puis gyro) : le fond franc gyro coupe l'arret comme le visuel. */
    private fun fusedSample(motion: FrameMotion?, gyro: GyroMotion?): MotionSample? =
        EgoMotion.fusedSample(motion?.global, gyro?.hint)

    /** Largeur luma du signal disponible (fond puis gyro), 0 sans signal. */
    private fun fusedLumaWidth(motion: FrameMotion?, gyro: GyroMotion?): Int =
        motion?.lumaWidth ?: gyro?.lumaWidth ?: 0

    /** Hauteur luma du signal disponible (fond puis gyro), 0 sans signal. */
    private fun fusedLumaHeight(motion: FrameMotion?, gyro: GyroMotion?): Int =
        motion?.lumaHeight ?: gyro?.lumaHeight ?: 0

    /** Delai inter-frames du signal disponible (ms, fond puis gyro), 0 sans signal. */
    private fun fusedDtMs(motion: FrameMotion?, gyro: GyroMotion?): Long =
        motion?.dtMs ?: gyro?.dtMs ?: 0L

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
                ?: bestDropped(vehicle.box, atMs, claimed)
            if (id == null) {
                val closeness = closenessOf(depthByBox, vehicle.key) // PISTE2
                tracks[nextId] = Track(vehicle.label, MotionModel(vehicle.box, atMs), atMs, closeness, closeness)
                nextId += 1
            } else if (id in dropped) {
                claimed += id
                val revived = dropped.remove(id)!!
                revived.track.label = vehicle.label
                revived.track.motion.observe(vehicle.box, atMs, maxSpeed)
                revived.track.lastSeenMs = atMs
                tracks[id] = revived.track
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

    /** Piste purgée la plus proche dans la fenêtre de réactivation, `null` sinon. */
    private fun bestDropped(box: Box, atMs: Long, claimed: Set<Int>): Int? {
        var bestId: Int? = null
        var bestIou = 0f
        for ((id, entry) in dropped) {
            if (id in claimed) continue
            if (atMs - entry.droppedAtMs > REACTIVATE_MS) continue
            val iou = box.iou(entry.track.motion.predictAt(atMs))
            if (iou > bestIou) {
                bestIou = iou
                bestId = id
            }
        }
        if (bestIou < IOU_MIN) return null
        return bestId
    }

    /** Piste autorisée à conclure : sous fond franc, mouvement véhicule prouvé exigé (le doute — verdict flou ou absent — ne crie jamais Stop). */
    private fun verdictAllows(id: Int): Boolean {
        if (!fondFranc) return true
        return lastVerdicts[id]?.verdict == Verdict.VEHICLE_MOVED
    }

    /** Oublie les pistes sans observation récente (objet sorti ou perdu). */
    private fun dropStale(atMs: Long) {
        val stale = tracks.filterValues { atMs - it.lastSeenMs > STALE_MS }.keys
        stale.forEach { id ->
            dropped[id] = DroppedTrack(tracks.getValue(id), atMs)
            tracks.remove(id)
        }
        dropped.entries.removeAll { atMs - it.value.droppedAtMs > REACTIVATE_MS }
    }

    /** Le plus dangereux des rapprochements francs, silence sinon. */
    private fun verdict(frameWidth: Int, frameHeight: Int, radialMpsByKey: Map<String, Float>?): ApproachVerdict {
        val approach = approachOf(frameWidth, frameHeight, radialMpsByKey)
        val lateral = lateralOf(frameWidth, frameHeight)
        return ApproachVerdict(
            approach.first, approach.second, approach.third,
            lateral.first, lateral.second, lateralTtcOf(frameWidth, frameHeight),
        )
    }

    /**
     * Rapprochement franc : plus petit tau sous [TTC_SECONDS], ou radiale
     * Kalman franche ([radialMpsByKey]) sans tau — la distance confirme,
     * jamais elle ne supprime.
     */
    private fun approachOf(frameWidth: Int, frameHeight: Int, radialMpsByKey: Map<String, Float>?): Triple<Boolean, Float?, String?> {
        var bestLabel: String? = null
        var bestTtc = Float.MAX_VALUE
        var radialLabel: String? = null
        var radialBest = Float.MAX_VALUE
        for ((id, track) in tracks) {
            if (!track.motion.velocityReliable) continue
            if (lastVerdicts[id]?.verdict == Verdict.CAMERA_MOVED) continue
            if (!verdictAllows(id)) continue
            if (fondFranc && onEdge(track.motion.anchorBox, frameWidth, frameHeight)) continue
            // PISTE2 : expansion à profondeur constante = pan/zoom caméra, pas une approche.
            if (DepthConfirm.contradicts(track.firstCloseness, track.lastCloseness)) continue
            val width = track.motion.anchorBox.width
            if (width < frameWidth * MIN_WIDTH_FRACTION) continue
            radialHit(track, frameWidth, radialMpsByKey)?.let { radial ->
                if (radial < radialBest) {
                    radialBest = radial
                    radialLabel = track.label
                }
            }
            if (track.motion.velocity.width <= frameWidth * MIN_GROWTH_RATIO) continue
            // PISTE1 : croissance expliquée par le zoom du fond — pas une approche.
            if (EgoCompensator.discountGrowth(track.motion.velocity.width / width, lastGlobal, lastGlobalDtMs).discounted) continue
            val tau = width / track.motion.velocity.width
            if (tau.isFinite() && tau < bestTtc) {
                bestTtc = tau
                bestLabel = track.label
            }
        }
        if (bestLabel != null && bestTtc < TTC_SECONDS) return Triple(true, bestTtc, bestLabel)
        if (radialLabel != null) return Triple(true, null, radialLabel)
        return Triple(false, null, null)
    }

    /**
     * Mouvement latéral franc : le plus rapide au-delà de [LATERAL_RATIO].
     * Comme l'approche, une piste embarquée par la caméra
     * ([Verdict.CAMERA_MOVED]) ne conclut pas — un panoramique n'est pas
     * une voiture qui roule.
     */
    private fun lateralOf(frameWidth: Int, frameHeight: Int): Pair<Boolean, String?> {
        var bestLabel: String? = null
        var bestSpeed = 0f
        for ((id, track) in tracks) {
            if (!track.motion.velocityReliable) continue
            if (lastVerdicts[id]?.verdict == Verdict.CAMERA_MOVED) continue
            if (!verdictAllows(id)) continue
            if (fondFranc && onEdge(track.motion.anchorBox, frameWidth, frameHeight)) continue
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

    /** TTC latéral (largeur / vitesse latérale), `null` sans latéral franc ([MIN_LATERAL_RATIO]). */
    private fun lateralTtcOf(frameWidth: Int, frameHeight: Int): Float? {
        var lateralTtc: Float? = null
        for ((id, track) in tracks) {
            if (!track.motion.velocityReliable) continue
            if (lastVerdicts[id]?.verdict == Verdict.CAMERA_MOVED) continue
            if (!verdictAllows(id)) continue
            if (fondFranc && onEdge(track.motion.anchorBox, frameWidth, frameHeight)) continue
            val width = track.motion.anchorBox.width
            if (width < frameWidth * MIN_WIDTH_FRACTION) continue
            lateralTtcOf(track, width, frameWidth)?.let { ttc ->
                if (lateralTtc == null || ttc < lateralTtc!!) lateralTtc = ttc
            }
        }
        return lateralTtc
    }

    /** TTC latéral d'une piste (largeur / |vitesse x|), `null` sans latéral franc. */
    private fun lateralTtcOf(track: Track, width: Float, frameWidth: Int): Float? {
        val lateral = abs(track.motion.velocity.x)
        if (lateral <= frameWidth * MIN_LATERAL_RATIO) return null
        return (width / lateral).takeIf { it.isFinite() }
    }

    /** Vitesse radiale d'une piste par sa clé d'instance, `null` sans signal franc. */
    private fun radialHit(track: Track, frameWidth: Int, radialMpsByKey: Map<String, Float>?): Float? {
        val radial = radialMpsByKey?.get(instanceKeyOf(track.label, track.motion.anchorBox.centerX / frameWidth))
        return radial?.takeIf { it.isFinite() && it <= RADIAL_APPROACH_MPS }
    }

    companion object {
        /** Sous ce temps-avant-collision (s), le véhicule force le veto. */
        const val TTC_SECONDS = 3.0f

        /** IoU minimal pour rattacher une boîte à une piste. */
        const val IOU_MIN = 0.2f

        /** Au-delà sans observation, la piste est oubliée. */
        const val STALE_MS = 1500L

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

        /** Piste purgée réactivable si elle réapparaît dans ce délai (retour rapide). */
        const val REACTIVATE_MS = 500L

        /** Vitesse radiale (m/s) sous laquelle l'approche est confirmée par la distance. */
        const val RADIAL_APPROACH_MPS = -1.0f

        /** Vitesse latérale minimale (fraction d'image/s) pour un TTC latéral. */
        const val MIN_LATERAL_RATIO = 0.01f

        /** Marge de détection du cadre (px) : à l'intérieur, la boîte est tronquée. */
        private const val EDGE_PX = 1f

        /**
         * Clé d'instance d'un véhicule : label canonique + seau de position
         * stable (gauche/centre/droite, frontières [PositionText.SIDE_EDGE]) —
         * deux voitures à deux endroits ne fusionnent plus. [centerX01] hors
         * bornes ou non fini = centre (doute stable).
         */
        fun instanceKeyOf(label: String, centerX01: Float): String {
            val x = centerX01.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
            val bucket = when {
                x < PositionText.SIDE_EDGE -> "L"
                x > 1f - PositionText.SIDE_EDGE -> "R"
                else -> "C"
            }
            return "${VehicleConcepts.keyOf(label)}@$bucket"
        }
    }
}

/**
 * Seuils d'expansion des véhicules (PISTE2) : un véhicule est un danger
 * grave — seuil de grossissement abaissé (0,02), couloir élargi (0,30, un
 * véhicule large menace même excentré), TTC plafonné à 4 s. Priors à recaler
 * sur téléphone, comme [VehicleApproach.TTC_SECONDS].
 */
val VEHICLE_APPROACH_THRESHOLDS = ApproachThresholds(
    minGrowthPerSec = 0.02f,
    centerMargin = 0.30f,
    maxTtcSec = 4f,
)

/**
 * Seuils d'expansion par label (PISTE2) : [vehicle] pour les labels de
 * [VehicleConcepts], [person] pour tout le reste.
 */
fun approachThresholdsFor(
    label: String,
    vehicle: ApproachThresholds = VEHICLE_APPROACH_THRESHOLDS,
    person: ApproachThresholds = ApproachThresholds(),
): ApproachThresholds = if (VehicleConcepts.isVehicle(label)) vehicle else person
