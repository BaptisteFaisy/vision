package dev.sam3vision.core.live

import dev.sam3vision.core.curb.CurbConcepts
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.hazard.HazardWatcher
import kotlin.math.abs

/** Ordre d'écart : [threat] entre dans le couloir, on s'en écarte vers [side]. */
data class AvoidOrder(val side: GuidanceSide, val threat: PlacedLabel) {
    /** Côté vu comme cap : la flèche et le décalage parlent en caps. */
    val cap: SteeringCap get() = if (side == GuidanceSide.Left) SteeringCap.Left else SteeringCap.Right
}

/**
 * Lecture du chemin d'une frame. [order] : un obstacle proche entre dans le
 * couloir, l'ordre parle à son niveau. [side] : le côté conseillé, ordre ou
 * obstacle lointain ; `null` quand rien ne gêne l'axe. [room] : la place vue.
 */
data class ClearView(val order: AvoidOrder?, val side: GuidanceSide?, val room: GapRoom? = null) {
    /** Côté vu comme cap, `null` sans conseil. */
    val cap: SteeringCap?
        get() = side?.let { if (it == GuidanceSide.Left) SteeringCap.Left else SteeringCap.Right }
}

/**
 * Le seul juge de l'écart latéral : qui entre dans le couloir, et de quel côté
 * sortir — le couloir, le décalage et le guetteur lisent cette même réponse,
 * deux voix ne se contredisent plus sur un obstacle.
 *
 * Tout se mesure en mètres sur les **bords de boîte** à la distance de l'objet.
 * Un centre de boîte ne dit rien d'une barrière large : le bout qui gêne peut
 * être à 2 m de lui. L'écart se compte de l'axe marché au bord de l'obstacle,
 * comme [PeoplePath.clearsSide], et l'ordre tient **tant que** l'écart manque :
 * c'est lui qui dit quand on est dégagé, pas la sortie d'une bande d'image.
 *
 * Un mur qu'on longe n'est pas un mur qu'on prend de face : sa boîte va du bord
 * du cadre au point de fuite ([isRail]). Son bord intérieur ne dit donc pas où
 * il est — seule sa distance au bord du cadre le situe ([railLateralM]). Pur et
 * testé en JVM.
 */
object ClearPath {

    /** Portée de l'ordre d'écart (m, ordre user du 04/11) : tout obstacle, dès 10 m. */
    const val ORDER_RANGE_M = 10f

    /** Écart voulu à un corps (m) : l'ordre user du 02/11, 1 m. */
    const val BODY_CLEARANCE_M = PeoplePath.MIN_CLEARANCE_M

    /** Écart voulu à un obstacle fixe (m) : poteau, barrière, mobilier, 1 m aussi. */
    const val STATIC_CLEARANCE_M = 1f

    /** Écart voulu à un mur longé (m) : on marche le long, sans le raser. */
    const val RAIL_CLEARANCE_M = 0.5f

    /** Distance supposée (m) d'un objet coupé par le bas : sa base est sous le cadre. */
    const val UNSEEN_DEPTH_M = 1.5f

    /** Bord de cadre (normalisé) : en deçà, la boîte sort de l'image. */
    const val FRAME_EDGE = 0.02f

    /** Tolérance d'axe d'un objet long mesuré dont l'approche est inconnue (normalisé, ≈ 2° de lacet). */
    const val AXIS_WOBBLE = 0.04f

    /** Tolérance d'axe quand rien ne dit « de face » : le lacet d'une marche (≈ 5°) balaie le point de fuite. */
    const val RAIL_SWAY = 0.10f

    /** Fermeture (m/s) dès laquelle un objet long n'est plus longé : on marche vers lui. */
    const val RAIL_MAX_CLOSING_MPS = 0.7f

    /** Temps de mesures (ms) sous lequel une fermeture ne dit rien d'un objet long. */
    const val RAIL_MIN_SPAN_MS = 800L

    private const val AXIS = 0.5f

    /** Lecture de la frame : ordre proche d'abord, chemin lointain sinon. */
    fun view(
        labels: List<PlacedLabel>,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        free: FreeSpace = FreeSpace.of(labels, hfovDeg),
        holeSide: GuidanceSide? = null,
    ): ClearView {
        val cleaned = labels.filter { Guidance.isObstacle(it) }
        val range = ORDER_RANGE_M
        val threat = threat(cleaned, hfovDeg, range)
        if (threat != null) {
            val side = GuidanceExit.side(cleaned, threat, holeSide, range, hfovDeg, free)
            return ClearView(AvoidOrder(side, threat), side, roomOf(cleaned, threat, range, hfovDeg))
        }
        val farRange = Guidance.AXIS_RANGE_METERS
        val far = threat(cleaned, hfovDeg, farRange) ?: return ClearView(null, null)
        val side = GuidanceExit.side(cleaned, far, holeSide, farRange, hfovDeg, free)
        return ClearView(null, side, roomOf(cleaned, far, farRange, hfovDeg))
    }

    /** Place de la frame autour de [obstacle] : ce que le journal montre, le côté s'y lit. */
    private fun roomOf(labels: List<PlacedLabel>, obstacle: PlacedLabel, range: Float, hfovDeg: Float): GapRoom? =
        WidestGap.of(labels, depthOf(obstacle, range) ?: range, hfovDeg)

    /** Obstacle le plus proche qui entre dans le couloir, `null` si tout passe. */
    fun threat(
        labels: List<PlacedLabel>,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        rangeMeters: Float = ORDER_RANGE_M,
    ): PlacedLabel? {
        val halfTangent = LensSpec.halfTangent(hfovDeg)
        if (!halfTangent.isFinite() || halfTangent <= 0f) return null
        return labels.filter { intrudes(it, halfTangent, rangeMeters) }
            .minByOrNull { nearDepth(it) ?: rangeMeters }
    }

    /** Vrai si [label] se contourne : une bordure se monte ou se descend, elle ne barre pas. */
    internal fun bars(label: PlacedLabel): Boolean = !CurbConcepts.isCurb(label.label)

    /** Vrai si [label] manque d'écart à l'axe marché, à portée. */
    private fun intrudes(label: PlacedLabel, halfTangent: Float, rangeMeters: Float): Boolean {
        if (!label.centerX.isFinite() || !bars(label)) return false
        if (isRail(label)) {
            val lateral = railLateralM(label, halfTangent) ?: return false
            return lateral < RAIL_CLEARANCE_M
        }
        val depth = nearDepth(label) ?: return false
        if (depth > rangeMeters) return false
        val clearance = if (PersonLabels.isBody(label.label)) BODY_CLEARANCE_M else STATIC_CLEARANCE_M
        return gapM(label, lateralPerX(depth, halfTangent)) < clearance
    }

    /** Écart de l'axe au bord de [label] (m), 0 si la boîte coupe l'axe. */
    private fun gapM(label: PlacedLabel, perX: Float): Float {
        val half = bodyHalfM(label, perX) / perX
        val low = label.centerX - half
        val high = label.centerX + half
        return when {
            low > AXIS -> (low - AXIS) * perX
            high < AXIS -> (AXIS - high) * perX
            else -> 0f
        }
    }

    /**
     * Distance de [label] pour juger de près : la mesure, sinon [UNSEEN_DEPTH_M]
     * s'il est coupé par le bas ; `null` = on ne sait pas qu'il est proche.
     */
    internal fun nearDepth(label: PlacedLabel): Float? {
        val meters = label.meters
        if (meters != null && meters.isFinite() && meters > 0f) return meters
        return if (label.cutBottom) UNSEEN_DEPTH_M else null
    }

    /**
     * Distance pour tracer une bande : celle de [nearDepth], sinon la portée
     * (boîte connue, objet quelque part devant) ; `null` sans bords, la
     * géométrie est incomplète.
     */
    internal fun depthOf(label: PlacedLabel, rangeMeters: Float): Float? {
        nearDepth(label)?.let { return it }
        if (label.x0 == null || label.x1 == null) return null
        return rangeMeters
    }

    /** Vrai si [label] borde le chemin sur sa longueur : mur, clôture, barrière. */
    private fun isLong(label: String): Boolean =
        HazardConcepts.levelOf(label) == HazardWatcher.LEVEL_FENCE

    /**
     * Vrai si [label] longe le marcheur : objet long, boîte qui sort du cadre sur
     * un côté et ne franchit pas l'axe. Franchir l'axe, c'est que le cap finit
     * dedans : on le prend de face, il reste un obstacle. Une boîte seule ne
     * sépare pas un mur longé d'une barrière de face dont on frôle le bout : le
     * mur reste à la même distance quand on avance, la barrière se rapproche
     * ([railWobble]).
     */
    internal fun isRail(label: PlacedLabel): Boolean {
        if (!isLong(label.label)) return false
        val wobble = railWobble(label) ?: return false
        val x0 = label.x0 ?: return false
        val x1 = label.x1 ?: return false
        val low = minOf(x0, x1)
        val high = maxOf(x0, x1)
        return (low <= FRAME_EDGE && high < AXIS + wobble) ||
            (high >= 1f - FRAME_EDGE && low > AXIS - wobble)
    }

    /**
     * Tolérance d'axe de [label], `null` s'il se rapproche : on marche vers lui.
     * Seul un objet mesuré dont on ne connaît pas encore l'approche reste à
     * [AXIS_WOBBLE] : sa boîte est tout ce qu'on sait, et elle peut être de face.
     */
    private fun railWobble(label: PlacedLabel): Float? {
        val closing = label.closingMps?.takeIf { label.closingSpanMs >= RAIL_MIN_SPAN_MS }
        if (closing == null) return if (label.meters == null) RAIL_SWAY else AXIS_WOBBLE
        return if (closing >= RAIL_MAX_CLOSING_MPS) null else RAIL_SWAY
    }

    /**
     * Distance latérale du rail (m) : sa mesure vient de sa partie la plus haute
     * à l'image, le bord du cadre, là où il est le plus proche. `null` sans mesure.
     */
    internal fun railLateralM(label: PlacedLabel, halfTangent: Float): Float? {
        val depth = label.meters?.takeIf { it.isFinite() && it > 0f } ?: return null
        val outer = if (label.centerX < AXIS) minOf(label.x0 ?: 0f, label.x1 ?: 0f)
        else maxOf(label.x0 ?: 1f, label.x1 ?: 1f)
        return abs(outer - AXIS) * lateralPerX(depth, halfTangent)
    }
}
