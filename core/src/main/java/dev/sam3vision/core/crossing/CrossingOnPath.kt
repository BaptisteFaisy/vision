package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.LateralNudgeTracker
import dev.sam3vision.core.live.PersonLabels
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.SegObject

/**
 * Personnes SUR le passage piéton : le prédicat spatial que la voix douce de
 * Traversée utilise pour dire « Gauche. » / « Droite. » d'évitement. Le test
 * est corps-qui-touche (la boîte de la personne touche celle du passage, à
 * [TOL] près — les pieds seuls rateraient quand ils sont cachés ou coupés :
 * masqués par une voiture, sous le cadre de près). Boîtes normalisées 0..1,
 * jamais de pixels bruts.
 *
 * Pur et testé en JVM : aucune image, aucun Android, aucune horloge implicite.
 */
object CrossingOnPath {

    /**
     * Marge du test corps-qui-touche : la même convention « touche » que le
     * bas du cadre ([CrossingGuidance.BOTTOM_TOUCH_FRACTION]) — ~10 px en x,
     * ~23 px en y à 512x1138, un septième de bande de cadran.
     */
    const val TOL = 1f - CrossingGuidance.BOTTOM_TOUCH_FRACTION

    /**
     * Index du meilleur passage (score max, [CrossingOffer.MIN_SCORE] min),
     * `-1` si aucun. Miroir de [CrossingGuidance.selectBest] en index, sans
     * allocation (boucle manuelle, premier max gagne comme `maxByOrNull`).
     */
    fun bestCrosswalk(objects: List<SegObject>, minScore: Float = CrossingOffer.MIN_SCORE): Int {
        var best = -1
        var i = 0
        while (i < objects.size) {
            val obj = objects[i]
            if (obj.score >= minScore && obj.box.size >= 4 &&
                CrossingOffer.isCrosswalkLabel(obj.label) && (best < 0 || obj.score > objects[best].score)
            ) {
                best = i
            }
            i++
        }
        return best
    }

    /**
     * Corps (`px0`, `py0`, `px1`, `py2`, normalisés) qui touche la boîte du
     * passage (`cx0`, `cy0`, `cx1`, `cy1`, normalisés), à [tol] près :
     * chevauchement des deux rectangles, pas d'IoU (une boîte verticale sur
     * une bande horizontale aurait une IoU ~0 même dessus). Géométrie
     * illisible = faux, jamais de consigne devinée.
     */
    fun bodyOnCrosswalk(
        px0: Float,
        py0: Float,
        px1: Float,
        py2: Float,
        cx0: Float,
        cy0: Float,
        cx1: Float,
        cy1: Float,
        tol: Float = TOL,
    ): Boolean {
        if (!px0.isFinite() || !py0.isFinite() || !px1.isFinite() || !py2.isFinite()) return false
        return px0 <= cx1 + tol && px1 >= cx0 - tol &&
            py0 <= cy1 + tol && py2 >= cy0 - tol
    }
}

/**
 * Suit les côtés du passage occupés par des personnes : un côté occupé
 * [confirmFrames] frames de suite conseille de passer de l'autre côté
 * ([SteeringCap] où aller, `null` = silence). Statique compris — c'est la
 * différence avec [LateralNudgeTracker] (approche seule, verrou
 * « statique → rien » intact) : quelqu'un d'immobile au milieu des
 * zébrures se contourne aussi.
 *
 * Règles partagées avec le nudge, jamais dupliquées : milieu
 * ([LateralNudgeTracker.SIDE_MID], pile au centre = couloir, pas côté),
 * portée ([LateralNudgeTracker.NUDGE_METERS], lue dans [readings] quand la
 * personne est mesurée — sans mesure, la boîte seule suffit, comme
 * [dev.sam3vision.core.live.Guidance] qui traite l'inconnu comme à portée),
 * péremption ([LateralNudgeTracker.STALE_MS]). Pas de fenêtre avant : la
 * boîte du passage borne déjà latéralement, et le couloir central
 * ([CrossingGuidance.LEFT_EDGE]-[CrossingGuidance.RIGHT_EDGE], le milieu de
 * l'image = l'axe de marche) ne garde que les occupants qui bouchent le
 * passage de l'utilisateur — un occupant sur les zébrures mais hors d'axe ne
 * déclenche pas d'évitement (les positions le disent au cadran). Les deux
 * côtés à la fois = silence (le couloir bouché parle s'il est bouché).
 *
 * Pur JVM, deux passes sans collection intermédiaire (boucles manuelles,
 * O(n), zéro allocation) : le meilleur passage, puis les occupants.
 */
class CrossingOnPathTracker(
    private val personMinScore: Float = CrossingOffer.MIN_SCORE,
    private val confirmFrames: Int = CrossingGuidance.ARRIVAL_CONFIRM_FRAMES,
) {

    private var leftHits = 0
    private var rightHits = 0
    private var leftLastMs = Long.MIN_VALUE
    private var rightLastMs = Long.MIN_VALUE

    /**
     * Frame à [atMs] : le côté où aller, ou `null` (silence — aucun occupant,
     * centre, deux côtés, trop loin, passage invisible). [objects] porte les
     * boîtes pixels (`width` x `height`), [readings] les distances quand elles
     * sont mesurées.
     */
    fun onFrame(
        atMs: Long,
        objects: List<SegObject>,
        width: Int,
        height: Int,
        readings: List<DistanceReading> = emptyList(),
    ): SteeringCap? {
        val crossIdx = if (width > 0 && height > 0) CrossingOnPath.bestCrosswalk(objects) else -1
        if (crossIdx < 0) {
            leftHits = 0
            rightHits = 0
            return null
        }
        val cross = objects[crossIdx].box
        val cx0 = cross[0] / width
        val cy0 = cross[1] / height
        val cx1 = cross[2] / width
        val cy1 = cross[3] / height
        var left = false
        var right = false
        var i = 0
        while (i < objects.size) {
            when (occupantSide(objects[i], width, height, cx0, cy0, cx1, cy1, readings)) {
                SteeringCap.Left -> left = true
                SteeringCap.Right -> right = true
                else -> Unit
            }
            i++
        }
        leftHits = nextHits(left, leftHits, leftLastMs, atMs)
        rightHits = nextHits(right, rightHits, rightLastMs, atMs)
        leftLastMs = atMs
        rightLastMs = atMs
        val leftOk = leftHits >= confirmFrames
        val rightOk = rightHits >= confirmFrames
        if (leftOk && rightOk) return null
        if (leftOk) return SteeringCap.Right
        if (rightOk) return SteeringCap.Left
        return null
    }

    /** Oublie les deux côtés (nouvelle séance, fin de traversée). */
    fun reset() {
        leftHits = 0
        rightHits = 0
        leftLastMs = Long.MIN_VALUE
        rightLastMs = Long.MIN_VALUE
    }

    /** Côté occupé par [obj] (`null` = pas un occupant qualifié). */
    private fun occupantSide(
        obj: SegObject,
        width: Int,
        height: Int,
        cx0: Float,
        cy0: Float,
        cx1: Float,
        cy1: Float,
        readings: List<DistanceReading>,
    ): SteeringCap? {
        if (obj.score < personMinScore || !PersonLabels.isPerson(obj.label)) return null
        val box = obj.box
        if (box.size < 4) return null
        val px0 = box[0] / width
        val py0 = box[1] / height
        val px1 = box[2] / width
        val py2 = box[3] / height
        if (!CrossingOnPath.bodyOnCrosswalk(px0, py0, px1, py2, cx0, cy0, cx1, cy1)) return null
        if (!closeEnough(box, readings)) return null
        val centerX = (px0 + px1) / 2f
        if (centerX < CrossingGuidance.LEFT_EDGE || centerX > CrossingGuidance.RIGHT_EDGE) return null
        if (centerX < LateralNudgeTracker.SIDE_MID) return SteeringCap.Left
        if (centerX > LateralNudgeTracker.SIDE_MID) return SteeringCap.Right
        return null
    }

    /** Assez près pour décaler : mesuré sous la portée, ou non mesuré (la boîte suffit). */
    private fun closeEnough(box: List<Float>, readings: List<DistanceReading>): Boolean {
        var i = 0
        while (i < readings.size) {
            val reading = readings[i]
            if (sameBox(reading.box, box)) {
                val meters = reading.meters
                return meters.isFinite() && meters <= LateralNudgeTracker.NUDGE_METERS
            }
            i++
        }
        return true
    }

    /** Même boîte, sans allocation (identité d'abord, quatre flottants sinon). */
    private fun sameBox(a: List<Float>, b: List<Float>): Boolean {
        if (a === b) return true
        if (a.size < 4 || b.size < 4) return false
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3]
    }

    /** Compte de confirmation : une absence retombe, un long silence repart de un. */
    private fun nextHits(observed: Boolean, hits: Int, lastMs: Long, atMs: Long): Int {
        if (!observed) return 0
        if (lastMs != Long.MIN_VALUE && atMs - lastMs > LateralNudgeTracker.STALE_MS) return 1
        return hits + 1
    }
}
