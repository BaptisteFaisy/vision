package dev.sam3vision.core.live

import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.GroundPlane
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Passage
import dev.sam3vision.core.model.SegObject
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Boîte en pixels de l'image, bornes incluses. */
data class PixelBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    fun toList(): List<Float> = listOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
}

/** Point en coordonnées caméra : x à droite, y vers le haut, z sur l'axe optique. */
private data class CameraPoint(val x: Double, val y: Double, val z: Double) {
    fun towards(other: CameraPoint, ratio: Double) = CameraPoint(
        x + (other.x - x) * ratio,
        y + (other.y - y) * ratio,
        z + (other.z - z) * ratio,
    )
}

/**
 * Caméra de poitrine : le calibrage de l'app ([LensSpec.Default], largeur de champ
 * 52,8° en portrait), porté à [heightM], tangage [pitchDeg] vers le bas.
 *
 * Chaque façade est découpée au cône de vue **avant** projection : un mur long rend
 * donc la boîte de sa partie visible, du bord d'image à son point le plus lointain, et
 * un objet derrière ou hors champ n'a pas de boîte. Les règles de boîte coupée restent
 * celles de `DistanceMeter`, qui reçoit ces boîtes telles quelles.
 */
class SimCamera(
    val width: Int = 384,
    val height: Int = 512,
    private val lens: LensSpec = LensSpec.Default,
    private val heightM: Double = 1.3,
    pitchDeg: Double = 5.0,
) {
    private val pitchRad = Math.toRadians(pitchDeg)
    private val focal = lens.focalPx(width, height).toDouble()
    private val centerX = lens.principalPx(width, height).first.toDouble()
    private val centerY = lens.principalPx(width, height).second.toDouble()

    /** Pose donnée à `DistanceMeter` : la vraie, sans erreur d'IMU. */
    val pose = CameraPose(heightM.toFloat(), pitchRad.toFloat())

    /** Champ porté par la largeur de l'image, celui que reçoit le guidage. */
    val hfovDeg: Float = lens.widthFovDeg(width, height)

    private val horizonRow =
        GroundPlane.horizonRow(pose, focal.toFloat(), centerY.toFloat()).toDouble()

    private val frustum: List<(CameraPoint) -> Double> = listOf(
        { it.z - NEAR_M },
        { focal * it.x + centerX * it.z },
        { (width - centerX) * it.z - focal * it.x },
        { centerY * it.z - focal * it.y },
        { (height - centerY) * it.z + focal * it.y },
    )

    /** Boîte réelle de [facade] vue de [at], `null` si rien n'entre dans l'image. */
    fun boxOf(facade: Facade, at: WalkPose): PixelBox? {
        val corners = listOf(
            toCamera(facade.ax, facade.ay, 0.0, at),
            toCamera(facade.bx, facade.by, 0.0, at),
            toCamera(facade.bx, facade.by, facade.heightM, at),
            toCamera(facade.ax, facade.ay, facade.heightM, at),
        )
        val seen = frustum.fold(corners) { polygon, plane -> clip(polygon, plane) }
        if (seen.size < 3) return null
        val columns = seen.map { centerX + focal * it.x / it.z }
        val rows = seen.map { centerY - focal * it.y / it.z }
        val box = PixelBox(columns.min(), rows.min(), columns.max(), rows.max())
        return box.takeIf { it.x1 - it.x0 >= MIN_SIDE_PX && it.y1 - it.y0 >= MIN_SIDE_PX }
    }

    /** La frame vue de [at] à [atMs] : détections perdues au tirage, boîtes bruitées. */
    fun shoot(obstacles: List<Obstacle>, at: WalkPose, atMs: Long, rng: Random): List<SegObject> =
        obstacles.mapNotNull { obstacle ->
            val lost = rng.nextDouble() < obstacle.lossRate
            val box = boxOf(obstacle.facadeFrom(at.x, at.y, atMs), at) ?: return@mapNotNull null
            val noisy = noisy(box, rng)
            if (lost) null else SegObject(obstacle.label, 1f, noisy.toList(), "")
        }

    /**
     * Bruit d'une boîte : distance ±[DISTANCE_SIGMA] (les lignes s'écartent de l'horizon
     * d'un même facteur, sténopé et plan de sol bougent ensemble) puis ±[EDGE_JITTER_PX]
     * par bord. Un bord coupé par l'image le reste.
     */
    fun noisy(box: PixelBox, rng: Random): PixelBox {
        val scale = 1.0 / max(MIN_DEPTH_FACTOR, 1.0 + DISTANCE_SIGMA * rng.nextGaussian())
        val x0 = jittered(box.x0, box.x0, 0.0, width, rng)
        val x1 = jittered(box.x1, box.x1, width.toDouble(), width, rng)
        val y0 = jittered(box.y0, horizonRow + (box.y0 - horizonRow) * scale, 0.0, height, rng)
        val y1 = jittered(box.y1, horizonRow + (box.y1 - horizonRow) * scale, height.toDouble(), height, rng)
        return PixelBox(x0, y0, max(x1, x0 + 1.0), max(y1, y0 + 1.0))
    }

    private fun jittered(raw: Double, moved: Double, cutAt: Double, size: Int, rng: Random): Double {
        val shifted = moved + EDGE_JITTER_PX * rng.nextGaussian()
        return if (abs(raw - cutAt) < CUT_EPS_PX) cutAt else shifted.coerceIn(0.0, size.toDouble())
    }

    private fun toCamera(x: Double, y: Double, z: Double, at: WalkPose): CameraPoint {
        val dx = x - at.x
        val dy = y - at.y
        val right = dx * cos(at.headingRad) - dy * sin(at.headingRad)
        val forward = dx * sin(at.headingRad) + dy * cos(at.headingRad)
        val up = z - heightM
        return CameraPoint(
            right,
            forward * sin(pitchRad) + up * cos(pitchRad),
            forward * cos(pitchRad) - up * sin(pitchRad),
        )
    }

    /** Sutherland-Hodgman : garde la partie de [polygon] où [plane] est positif. */
    private fun clip(polygon: List<CameraPoint>, plane: (CameraPoint) -> Double): List<CameraPoint> {
        val kept = ArrayList<CameraPoint>(polygon.size + 1)
        for (index in polygon.indices) {
            val from = polygon[index]
            val to = polygon[(index + 1) % polygon.size]
            val fromSide = plane(from)
            val toSide = plane(to)
            if (fromSide >= 0.0) kept += from
            if (fromSide * toSide < 0.0) kept += from.towards(to, fromSide / (fromSide - toSide))
        }
        return kept
    }

    private companion object {
        /** Plan proche (m) : en deçà, plus rien de projetable. */
        const val NEAR_M = 0.1

        /** Sous ce côté (px), SAM ne sort pas de boîte. */
        const val MIN_SIDE_PX = 2.0

        /** Écart-type relatif de la distance. */
        const val DISTANCE_SIGMA = 0.10

        /** Écart-type du tremblé par bord (px). */
        const val EDGE_JITTER_PX = 2.0

        /** Facteur de profondeur plancher : le bruit ne retourne jamais la boîte. */
        const val MIN_DEPTH_FACTOR = 0.5

        /** Tolérance pour reconnaître un bord posé sur le cadre (px). */
        const val CUT_EPS_PX = 1e-6
    }
}

/**
 * Port de `server/backend/passage.py` sur les boîtes de la frame : bande de centre,
 * nains ignorés, côté par `_freer_side`. Bloqueurs : personnes seules, comme la version
 * déployée (barrière et poteau n'y sont pas encore).
 */
object ServerPassage {
    private const val SIDE_EDGE = 0.34f
    private const val MIN_HEIGHT_RATIO = 0.02f
    private val BLOCKERS = setOf("person")

    private class Blocker(val centerX: Float, val heightPx: Float, val obj: SegObject)

    /** Passage conseillé : bouché et côté, libre sinon. */
    fun suggest(objects: List<SegObject>, width: Int, height: Int): Passage {
        val blockers = blockersOf(objects, width, height)
        val blocking = blockers.filter { it.centerX in SIDE_EDGE..1f - SIDE_EDGE }
        if (blocking.isEmpty()) return Passage(blocked = false)
        val obstacle = blocking.minWith(
            compareBy({ -it.heightPx }, { abs(it.centerX - 0.5f) }, { it.obj.label }),
        )
        return Passage(true, freerSide(blockers, obstacle), obstacle.obj.label, obstacle.obj.box, obstacle.obj.score)
    }

    private fun blockersOf(objects: List<SegObject>, width: Int, height: Int): List<Blocker> =
        objects.filter { it.label in BLOCKERS }.mapNotNull { obj ->
            val heightPx = obj.box[3] - obj.box[1]
            if (heightPx < MIN_HEIGHT_RATIO * height) null
            else Blocker((obj.box[0] + obj.box[2]) / 2f / width, heightPx, obj)
        }

    private fun freerSide(blockers: List<Blocker>, obstacle: Blocker): String {
        val left = blockers.filter { it.centerX < SIDE_EDGE }
        val right = blockers.filter { it.centerX > 1f - SIDE_EDGE }
        if (left.size != right.size) return if (left.size < right.size) "left" else "right"
        val nearestLeft = left.maxOfOrNull { it.heightPx } ?: 0f
        val nearestRight = right.maxOfOrNull { it.heightPx } ?: 0f
        if (nearestLeft != nearestRight) return if (nearestLeft < nearestRight) "left" else "right"
        return if (obstacle.centerX < 0.5f) "right" else "left"
    }
}
