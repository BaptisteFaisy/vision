package dev.sam3vision.core.cycle

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.predict.BoxPredictor
import dev.sam3vision.core.predict.MotionModel
import kotlin.math.abs

/**
 * Verdict mouvement des cycles, par clé canonique ([CycleConcepts.keyOf]) :
 * qui roule, qui est à l'arrêt, qui porte un cavalier probable.
 *
 * Une clé jeune (vitesse non établie) n'est ni en mouvement ni à l'arrêt :
 * le doute par défaut, jamais d'arrêt inventé. Deux engins de même label
 * peuvent mettre la clé dans les deux ensembles — l'annonce donne alors la
 * priorité au mouvement (le danger parle d'abord).
 */
data class CycleVerdict(
    /** Clés en mouvement : latéral franc, approche franche ou cavalier probable. */
    val movingKeys: Set<String>,
    /** Clés à l'arrêt : vitesse établie, aucun critère de mouvement. */
    val stillKeys: Set<String>,
    /** Clés dont la boîte chevauche une personne (cavalier probable). */
    val riderKeys: Set<String>,
    /** Clés en approche franche (croissance) — sert le drapeau urgent. */
    val approachKeys: Set<String>,
) {
    companion object {
        /** Verdict vide : rien en mouvement, rien à l'arrêt. */
        fun none(): CycleVerdict = CycleVerdict(emptySet(), emptySet(), emptySet(), emptySet())
    }
}

/**
 * Mouvement des vélos et trottinettes sur boîtes SAM, miroir de
 * `VehicleApproach` : le direct `/live` ne donnant pas d'ids stables,
 * l'association se fait ici par IoU entre frames, puis chaque piste porte un
 * [MotionModel] (défauts : blend 0,2, bêta 0,3, IoU min 0,15) dont on tire
 * les critères de mouvement.
 *
 * Un cycle roule quand sa vitesse latérale dépasse [LATERAL_RATIO] (3 % de
 * l'image/s — il traverse le champ), quand sa largeur croît au-delà de
 * [GROWTH_RATIO] (il s'approche, même seuil que le veto) ou quand une
 * personne chevauche sa boîte au-delà de [RIDER_IOU] (heuristique cavalier :
 * quelqu'un dessus = roule probable). Sinon, vitesse établie et aucun
 * critère : à l'arrêt.
 *
 * Seuils = priors à recaler sur téléphone, direction sûre : une piste jeune
 * ou perdue ne tranche jamais. Pur Kotlin testé en JVM.
 */
class CycleMotion {

    private class Track(
        var label: String,
        val motion: MotionModel,
        var lastSeenMs: Long,
        var rider: Boolean = false,
    )

    private data class CycleBox(val label: String, val box: Box)

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 0

    /** Pistes en cours (après purge) — exposé pour les tests. */
    val trackCount: Int get() = tracks.size

    /** Oublie toutes les pistes (nouvelle séance de direct). */
    fun reset() {
        tracks.clear()
        nextId = 0
    }

    /** Verdict pour une frame datée de [atMs] (ms, monotone). */
    fun update(detections: List<Detection>, atMs: Long): CycleVerdict {
        val frameWidth = detections.maxOfOrNull { it.width }?.takeIf { it > 0 } ?: FALLBACK_WIDTH
        associate(cyclesOf(detections), atMs, frameWidth)
        dropStale(atMs)
        flagRiders(personBoxes(detections))
        return verdict(frameWidth)
    }

    /** Cycles confiants à boîte saine, seuls suivis. */
    private fun cyclesOf(detections: List<Detection>): List<CycleBox> =
        detections.mapNotNull { detection ->
            if (!CycleConcepts.isCycle(detection.label)) return@mapNotNull null
            if (detection.score <= SCORE_MIN) return@mapNotNull null
            val box = Box.fromProto(detection.box) ?: return@mapNotNull null
            if (!box.isSane) return@mapNotNull null
            CycleBox(detection.label, box)
        }

    /** Boîtes des personnes confiantes, pour l'heuristique cavalier. */
    private fun personBoxes(detections: List<Detection>): List<Box> =
        detections.mapNotNull { detection ->
            if (!isPerson(detection.label)) return@mapNotNull null
            if (detection.score <= SCORE_MIN) return@mapNotNull null
            Box.fromProto(detection.box)?.takeIf { it.isSane }
        }

    /** Vrai si [label] désigne une personne (FR/EN, pluriel simple neutralisé). */
    private fun isPerson(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key in PERSON_KEYS) return true
        return key.endsWith("s") && key.length > 3 && key.dropLast(1) in PERSON_KEYS
    }

    /** Associe chaque cycle à sa piste (meilleur IoU) ou en crée une. */
    private fun associate(cycles: List<CycleBox>, atMs: Long, frameWidth: Int) {
        val maxSpeed = frameWidth * BoxPredictor.DEFAULT_MAX_SPEED_RATIO
        val claimed = HashSet<Int>()
        for (cycle in cycles) {
            val id = bestTrack(cycle.box, atMs, claimed)
            if (id == null) {
                tracks[nextId] = Track(cycle.label, MotionModel(cycle.box, atMs), atMs)
                nextId += 1
            } else {
                claimed += id
                val track = tracks.getValue(id)
                track.label = cycle.label
                track.motion.observe(cycle.box, atMs, maxSpeed)
                track.lastSeenMs = atMs
            }
        }
    }

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

    /** Marque les pistes chevauchées par une personne (cavalier probable). */
    private fun flagRiders(persons: List<Box>) {
        for ((_, track) in tracks) {
            track.rider = persons.any { it.iou(track.motion.anchorBox) > RIDER_IOU }
        }
    }

    /** Clés en mouvement et à l'arrêt — les pistes jeunes ne tranchent pas. */
    private fun verdict(frameWidth: Int): CycleVerdict {
        val moving = LinkedHashSet<String>()
        val still = LinkedHashSet<String>()
        val riders = LinkedHashSet<String>()
        val approaches = LinkedHashSet<String>()
        for ((_, track) in tracks) {
            val key = CycleConcepts.keyOf(track.label)
            if (track.rider) riders += key
            if (!track.motion.velocityReliable) continue
            val lateral = abs(track.motion.velocity.x) > frameWidth * LATERAL_RATIO
            val approach = track.motion.velocity.width > frameWidth * GROWTH_RATIO
            if (approach) approaches += key
            if (lateral || approach || track.rider) moving += key else still += key
        }
        return CycleVerdict(moving, still, riders, approaches)
    }

    companion object {
        /** Score minimal d'une détection prise au sérieux (miroir du veto). */
        const val SCORE_MIN = 0.5f

        /** IoU minimal pour rattacher une boîte à une piste (miroir approche). */
        const val IOU_MIN = 0.3f

        /** Au-delà sans observation, la piste est oubliée (miroir approche). */
        const val STALE_MS = 1000L

        /** Vitesse latérale franche : 3 % de la largeur d'image par seconde. */
        const val LATERAL_RATIO = 0.03f

        /** Croissance franche : 0,5 % de l'image/s (miroir approche). */
        const val GROWTH_RATIO = 0.005f

        /** Chevauchement personne/cycle au-delà duquel le cavalier est probable. */
        const val RIDER_IOU = 0.1f

        /** Largeur d'image de repli quand les détections ne la donnent pas. */
        const val FALLBACK_WIDTH = 1000

        /** Personnes FR/EN (canoniques) pour l'heuristique cavalier. */
        private val PERSON_KEYS = setOf(
            "person", "personne", "humain", "human",
            "homme", "man", "femme", "woman",
            "enfant", "child", "kid",
        )
    }
}
