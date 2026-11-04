package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.predict.Box
import kotlin.math.abs
import kotlin.math.hypot

/** Déplacement de la caméra depuis la frame précédente, en fractions d'image. */
class CameraStep(val dx: Float, val dy: Float)

/**
 * Mouvement de la caméra lu sur les objets fixes que SAM rend déjà : poteaux,
 * panneaux, feux, murs. Quand ils glissent tous ensemble dans l'image, c'est le
 * téléphone qui balaie, pas la rue. Aucun capteur (décision 100 % visuelle) et
 * aucun pixel : quelques comparaisons sur les boîtes de la frame.
 *
 * `BlockMotionSource` se tait sur un balayage lent : à 0,1 image/s, une voiture
 * garée dérive pourtant au-dessus de [ParkedProof.PROOF_SPEED], et « Don't
 * cross. » sortait devant des garées. Mesuré sur des boîtes SAM réelles : 11 à
 * 17 objets fixes par frame, un panneau tremble de 0,01 à 0,02 image/s.
 *
 * Un repère ne compte jamais s'il est véhicule ou posé sur un véhicule : une
 * file de voitures qui roule ne peut donc pas passer pour un balayage. Un bord
 * coupé par le cadre ne vote pas, l'autre si (un poteau coupé en haut dit
 * encore sa place en largeur). Une voiture qui roule garde son mouvement propre dans le repère de
 * la rue, même si la caméra la suit. Sans [MIN_VOTES] repères d'accord,
 * [update] rend `null` : le juge retombe sur le brut, comme avant.
 *
 * Pur Kotlin testé en JVM, aucune horloge implicite.
 */
class AnchorMotion {

    /** Bords d'une boîte sur un axe, `null` quand le cadre les coupe : ils ne disent rien de l'objet. */
    private class Span(val low: Float?, val high: Float?) {
        val size: Float? get() = if (low != null && high != null) high - low else null
        fun moved(by: Float) = Span(low?.minus(by), high?.minus(by))
    }

    /** Repère fixe, bords gardés dans le repère de la rue (image − caméra). */
    private class Anchor(val label: String, var x: Span, var y: Span, var seenMs: Long)

    /** Repère de la frame, en coordonnées d'image normalisées. */
    private class Seen(val label: String, val x: Span, val y: Span)

    /** Position de caméra datée, pour lire la vitesse sur [SPEED_WINDOW_MS]. */
    private class Mark(val atMs: Long, val x: Float, val y: Float)

    /** Position de caméra proposée par un repère revu, par axe. */
    private class Vote(val anchor: Anchor, val seen: Seen, val x: Float?, val y: Float?)

    private val anchors = ArrayList<Anchor>()
    private val marks = ArrayDeque<Mark>()
    private var cameraX = 0f
    private var cameraY = 0f
    private var speedX = 0f
    private var speedY = 0f
    private var lastMs = -1L

    /** Balayage prouvé à la dernière frame, au format du fond ; `null` caméra posée ou doute. */
    @Volatile
    var motion: FrameMotion? = null
        private set

    /** Oublie repères et position (nouvelle traversée, nouvelle séance). */
    @Synchronized
    fun reset() {
        anchors.clear()
        marks.clear()
        cameraX = 0f
        cameraY = 0f
        speedX = 0f
        speedY = 0f
        lastMs = -1L
        motion = null
    }

    /**
     * Avance d'une frame datée de [atMs] : déplacement de la caméra depuis la
     * frame d'avant, `null` sans date ou sans accord des repères en largeur, l'axe
     * d'un balayage : le doute y garde les protections du pan rapide. Sans accord
     * en hauteur, la hauteur compte pour zéro et reste au brut.
     */
    @Synchronized
    fun update(detections: List<Detection>, atMs: Long): CameraStep? {
        if (atMs < 0L || (lastMs >= 0L && atMs - lastMs > STALE_MS)) reset()
        motion = null
        if (atMs < 0L) return null
        val dtMs = if (lastMs < 0L) 0L else atMs - lastMs
        lastMs = atMs
        anchors.removeAll { atMs - it.seenMs > STALE_MS }
        val seen = anchorsOf(detections)
        val votes = votesOf(seen, dtMs)
        val x = consensusOf(votes.mapNotNull { it.x })
        val y = consensusOf(votes.mapNotNull { it.y })
        if (x == null) {
            speedX = 0f
            speedY = 0f
            if (anchors.isEmpty()) seed(seen, atMs)
            return null
        }
        val step = CameraStep(x - cameraX, (y ?: cameraY) - cameraY)
        moveTo(step, dtMs, atMs)
        votes.forEach { refine(it, x, y, atMs) }
        val known = votes.map { it.seen }
        seed(seen.filterNot { it in known }, atMs)
        motion = motionOf(step, detections, dtMs, votes.size)
        return step
    }

    /** Médiane si [MIN_VOTES] propositions s'y accordent, et au moins la moitié. */
    private fun consensusOf(values: List<Float>): Float? {
        if (values.size < MIN_VOTES) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val center = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
        val agreeing = values.count { abs(it - center) <= TOLERANCE }
        return center.takeIf { agreeing >= MIN_VOTES && agreeing * 2 >= values.size }
    }

    private fun moveTo(step: CameraStep, dtMs: Long, atMs: Long) {
        cameraX += step.dx
        cameraY += step.dy
        speedX = if (dtMs > 0L) step.dx / dtMs else 0f
        speedY = if (dtMs > 0L) step.dy / dtMs else 0f
        marks.addLast(Mark(atMs, cameraX, cameraY))
        while (atMs - marks.first().atMs > SPEED_WINDOW_MS) marks.removeFirst()
    }

    /** Lisse le repère qui s'accorde : le tremblé de SAM ne s'accumule pas ; l'écarté n'est pas revu. */
    private fun refine(vote: Vote, x: Float?, y: Float?, atMs: Long) {
        val onX = x != null && vote.x != null && abs(vote.x - x) <= TOLERANCE
        val onY = y != null && vote.y != null && abs(vote.y - y) <= TOLERANCE
        if (onX) vote.anchor.x = blended(vote.anchor.x, vote.seen.x.moved(cameraX))
        if (onY) vote.anchor.y = blended(vote.anchor.y, vote.seen.y.moved(cameraY))
        if (onX || onY) vote.anchor.seenMs = atMs
    }

    private fun blended(old: Span, new: Span) = Span(blended(old.low, new.low), blended(old.high, new.high))

    private fun blended(old: Float?, new: Float?): Float? = when {
        old == null -> new
        new == null -> old
        else -> old + (new - old) * SMOOTHING
    }

    private fun seed(seen: List<Seen>, atMs: Long) {
        seen.forEach { anchors += Anchor(it.label, it.x.moved(cameraX), it.y.moved(cameraY), atMs) }
    }

    /** Chaque repère revu propose une position de caméra : image − rue, sur ses bords libres. */
    private fun votesOf(seen: List<Seen>, dtMs: Long): List<Vote> {
        val expectedX = cameraX + speedX * dtMs
        val expectedY = cameraY + speedY * dtMs
        val free = anchors.toMutableList()
        return seen.mapNotNull { item ->
            val match = free.filter { it.label == item.label && similar(it.x, item.x) && similar(it.y, item.y) }
                .mapNotNull { anchor -> gapOf(anchor, item, expectedX, expectedY)?.let { anchor to it } }
                .filter { (_, gap) -> gap <= MATCH_RADIUS }
                .minByOrNull { (_, gap) -> gap }?.first ?: return@mapNotNull null
            free -= match
            Vote(match, item, shiftOf(match.x, item.x), shiftOf(match.y, item.y))
        }
    }

    /** Écart à la position attendue, `null` si aucun bord n'est comparable. */
    private fun gapOf(anchor: Anchor, item: Seen, expectedX: Float, expectedY: Float): Float? {
        val x = shiftOf(anchor.x, item.x)?.minus(expectedX)
        val y = shiftOf(anchor.y, item.y)?.minus(expectedY)
        if (x == null && y == null) return null
        return hypot(x ?: 0f, y ?: 0f)
    }

    /** Décalage moyen des bords libres des deux côtés, `null` s'il n'y en a aucun. */
    private fun shiftOf(street: Span, image: Span): Float? {
        val low = if (street.low != null && image.low != null) image.low - street.low else null
        val high = if (street.high != null && image.high != null) image.high - street.high else null
        return if (low != null && high != null) (low + high) / 2f else low ?: high
    }

    private fun similar(a: Span, b: Span): Boolean {
        val first = a.size ?: return true
        val second = b.size ?: return true
        return first > 0f && second > 0f && maxOf(first, second) / minOf(first, second) <= SIZE_RATIO
    }

    /** Repères de la frame : objets fixes, hors véhicules et hors de ce que ceux-ci couvrent. */
    private fun anchorsOf(detections: List<Detection>): List<Seen> {
        val vehicles = detections.filter { VehicleConcepts.isVehicle(it.label) }.mapNotNull(::normalized)
        return detections.filter { it.label.trim().lowercase() in LABELS && it.score > TraverseVeto.SCORE_MIN }
            .mapNotNull { detection -> seenOf(detection, vehicles) }
    }

    private fun seenOf(detection: Detection, vehicles: List<Box>): Seen? {
        val box = normalized(detection) ?: return null
        if (box.area > MAX_AREA || vehicles.any { covers(it, box) }) return null
        val x = spanOf(detection.box[0], detection.box[2], detection.width)
        val y = spanOf(detection.box[1], detection.box[3], detection.height)
        return Seen(detection.label.trim().lowercase(), x, y)
    }

    private fun spanOf(low: Float, high: Float, size: Int) = Span(
        (low / size).takeIf { low > EDGE_PX },
        (high / size).takeIf { high < size - EDGE_PX },
    )

    private fun covers(vehicle: Box, anchor: Box): Boolean {
        val width = minOf(vehicle.x2, anchor.x2) - maxOf(vehicle.x1, anchor.x1)
        val height = minOf(vehicle.y2, anchor.y2) - maxOf(vehicle.y1, anchor.y1)
        return width > 0f && height > 0f && width * height > anchor.area * COVER_MAX
    }

    private fun normalized(detection: Detection): Box? {
        val box = detection.normalizedBox() ?: return null
        return Box(box[0], box[1], box[2], box[3]).takeIf { it.isSane }
    }

    /** Format du fond, seulement si la caméra bouge franchement sur [SPEED_WINDOW_MS]. */
    private fun motionOf(step: CameraStep, detections: List<Detection>, dtMs: Long, votes: Int): FrameMotion? {
        val first = marks.first()
        val last = marks.last()
        val seconds = (last.atMs - first.atMs) / MS_PER_SECOND
        if (dtMs <= 0L || seconds <= 0f || votes == 0) return null
        if (hypot(last.x - first.x, last.y - first.y) / seconds < MIN_SPEED) return null
        val frame = detections.first()
        return FrameMotion(MotionSample(step.dx * frame.width, step.dy * frame.height, 1f, 1f), frame.width, frame.height, dtMs)
    }

    companion object {
        /** Repères qui se comptent : ce qui ne roule pas. */
        private val LABELS = setOf(
            "pole", "sign", "pedestrian traffic light", "street lamp", "trash can",
            "fence", "barrier", "wall", "curb", "crosswalk", "sidewalk", "stairs",
        )

        /** Repères d'accord exigés : deux pourraient être le même objet rendu deux fois. */
        const val MIN_VOTES = 3

        /** Écart toléré à la médiane (fraction d'image) : le tremblé de SAM sur une frame. */
        const val TOLERANCE = 0.02f

        /** Écart maximal à la position attendue (fraction d'image). */
        const val MATCH_RADIUS = 0.08f

        /** Vitesse caméra sous laquelle rien ne change pour le fond (image/s) : le seuil franc de [VehicleBackground]. */
        const val MIN_SPEED = VehicleBackground.LATERAL_RATIO

        /** Fenêtre de lecture de la vitesse (ms) : une frame de bruit n'y fait pas un balayage. */
        const val SPEED_WINDOW_MS = 600L

        /** Sans repère depuis ce délai, tout repart de zéro (ms). */
        const val STALE_MS = 1_500L

        private const val SIZE_RATIO = 1.6f
        private const val MAX_AREA = 0.25f
        private const val COVER_MAX = 0.3f
        private const val SMOOTHING = 0.3f
        private const val EDGE_PX = 1f
        private const val MS_PER_SECOND = 1000f
    }
}
