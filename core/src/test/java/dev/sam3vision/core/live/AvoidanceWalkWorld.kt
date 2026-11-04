package dev.sam3vision.core.live

import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Pose vue de dessus : position (m) et cap (rad, 0 = +y, positif vers la droite). */
data class WalkPose(val x: Double, val y: Double, val headingRad: Double)

/** Rectangle vertical posé au sol entre deux points : ce que la caméra projette. */
data class Facade(val ax: Double, val ay: Double, val bx: Double, val by: Double, val heightM: Double)

/** Un obstacle du monde : sa surface pour l'écart, sa façade pour la caméra. */
sealed interface Obstacle {
    val label: String

    /** Probabilité de perdre la détection sur une frame. */
    val lossRate: Double

    /** Distance (m) de ([x], [y]) à la surface, à l'instant [atMs]. */
    fun gapFrom(x: Double, y: Double, atMs: Long): Double

    /** Façade vue depuis ([x], [y]) à l'instant [atMs]. */
    fun facadeFrom(x: Double, y: Double, atMs: Long): Facade

    /** Ordonnée la plus lointaine de l'obstacle à l'instant [atMs]. */
    fun farY(atMs: Long): Double
}

/** Obstacle rond (poteau, personne), mobile sur l'axe y à [speedY] m/s. */
data class Disc(
    val x: Double,
    val y: Double,
    val radiusM: Double,
    val heightM: Double,
    override val label: String,
    override val lossRate: Double,
    val speedY: Double = 0.0,
) : Obstacle {
    private fun yAt(atMs: Long): Double = y + speedY * atMs / 1_000.0

    override fun gapFrom(x: Double, y: Double, atMs: Long): Double =
        hypot(x - this.x, y - yAt(atMs)) - radiusM

    /** Disque vu de face : sa largeur, perpendiculaire au rayon de visée. */
    override fun facadeFrom(x: Double, y: Double, atMs: Long): Facade {
        val centerY = yAt(atMs)
        val dx = this.x - x
        val dy = centerY - y
        val norm = max(hypot(dx, dy), 1e-6)
        val px = dy / norm * radiusM
        val py = -dx / norm * radiusM
        return Facade(this.x - px, centerY - py, this.x + px, centerY + py, heightM)
    }

    override fun farY(atMs: Long): Double = yAt(atMs) + radiusM
}

/** Obstacle droit (barrière, mur), sans épaisseur, entre deux points. */
data class Panel(
    val ax: Double,
    val ay: Double,
    val bx: Double,
    val by: Double,
    val heightM: Double,
    override val label: String,
    override val lossRate: Double,
) : Obstacle {
    /** Distance au segment : projection bornée aux deux extrémités. */
    override fun gapFrom(x: Double, y: Double, atMs: Long): Double {
        val dx = bx - ax
        val dy = by - ay
        val along = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(x - (ax + along * dx), y - (ay + along * dy))
    }

    override fun facadeFrom(x: Double, y: Double, atMs: Long): Facade = Facade(ax, ay, bx, by, heightM)

    override fun farY(atMs: Long): Double = max(ay, by)
}

private const val POLE_LOSS = 0.15
private const val OTHER_LOSS = 0.03

fun pole(x: Double, y: Double): Obstacle = Disc(x, y, 0.05, 3.0, "pole", POLE_LOSS)

fun person(x: Double, y: Double, speedY: Double = 0.0): Obstacle =
    Disc(x, y, 0.25, 1.70, "person", OTHER_LOSS, speedY)

fun barrier(fromX: Double, toX: Double, y: Double): Obstacle =
    Panel(fromX, y, toX, y, 1.0, "barrier", OTHER_LOSS)

fun wall(ax: Double, ay: Double, bx: Double, by: Double): Obstacle =
    Panel(ax, ay, bx, by, 2.5, "wall", OTHER_LOSS)

/** Une scène : des obstacles, et [calm] quand rien ne doit écarter le marcheur. */
data class WalkScene(
    val id: String,
    val title: String,
    val obstacles: List<Obstacle>,
    val calm: Boolean = false,
) {
    /** Ordonnée où la scène s'arrête : dernier obstacle dépassé de [WalkModel.PAST_LAST_M]. */
    fun endY(atMs: Long): Double = obstacles.maxOf { it.farY(atMs) } + WalkModel.PAST_LAST_M
}

private const val AHEAD_M = 8.0
private const val WALL_FROM_Y = -2.0
private const val WALL_TO_Y = 30.0

private fun longWall(x: Double): Obstacle = wall(x, WALL_FROM_Y, x, WALL_TO_Y)

/** Les quatorze scènes, marcheur parti de (0, 0), cap +y. */
val WALK_SCENES: List<WalkScene> = listOf(
    WalkScene("S1", "poteau axe", listOf(pole(0.0, AHEAD_M))),
    WalkScene("S2", "poteau 0,30 G", listOf(pole(-0.30, AHEAD_M))),
    WalkScene("S3", "poteau 0,30 D", listOf(pole(0.30, AHEAD_M))),
    WalkScene("S4", "personne axe", listOf(person(0.0, AHEAD_M))),
    WalkScene("S5", "personne 0,5 D", listOf(person(0.5, AHEAD_M))),
    WalkScene("S6", "personne de face", listOf(person(0.0, AHEAD_M, speedY = -1.0))),
    WalkScene("S7", "barrière sortie G", listOf(barrier(-0.5, 3.0, AHEAD_M))),
    WalkScene("S8", "barrière sortie D", listOf(barrier(-3.0, 0.5, AHEAD_M))),
    WalkScene("S9", "mur frontal sortie D", listOf(wall(-6.0, AHEAD_M, 0.6, AHEAD_M))),
    WalkScene("S10", "mur parallèle G", listOf(longWall(-0.8)), calm = true),
    WalkScene("S11", "couloir 2,4 m", listOf(longWall(-1.2), longWall(1.2)), calm = true),
    WalkScene("S12", "mur G + poteau", listOf(longWall(-0.8), pole(0.2, AHEAD_M))),
    WalkScene("S13", "poteau + personne D", listOf(pole(0.0, AHEAD_M), person(1.2, AHEAD_M))),
    WalkScene("S14", "barrière axe", listOf(barrier(-0.4, 0.4, AHEAD_M))),
)

/** Côté d'un mot parlé (-1 gauche, +1 droite), `null` sinon : SAM ou TwinLite. */
fun sideOf(word: String): Int? = when (word.lowercase().trimEnd('.')) {
    "left" -> -1
    "right" -> 1
    else -> null
}

/**
 * Le marcheur : obéit au dernier mot entendu, sans rien savoir des obstacles. Un mot de
 * côté le fait virer de [TURN_DEG] en [TURN_S] et tenir ce cap, [HOLD_MS] après le
 * dernier mot il revient vers +y à [RETURN_DEG_S] ; « Straight. » l'y renvoie aussitôt.
 * Le tremblement ([TREMOR_DEG], tiré à chaque pas) s'ajoute au cap commandé.
 */
class Walker(private val rng: Random) {
    var x = 0.0
        private set
    var y = 0.0
        private set

    /** Cap commandé (degrés), hors tremblement. */
    var commandDeg = 0.0
        private set

    private var tremorDeg = 0.0
    private var side = 0
    private var lastSideMs = 0L

    /** Pose courante, tremblement compris : c'est elle que voit la caméra. */
    fun pose(): WalkPose = WalkPose(x, y, Math.toRadians(commandDeg + tremorDeg))

    /** Applique un mot entendu à [nowMs] ; un mot inconnu ne change rien. */
    fun hear(word: String, nowMs: Long) {
        val heard = sideOf(word)
        if (heard != null) {
            side = heard
            lastSideMs = nowMs
        } else if (word.lowercase().startsWith("straight")) {
            side = 0
        }
    }

    /** Avance de [dtMs] : met le cap à jour, tire le tremblement, marche. */
    fun step(dtMs: Long, nowMs: Long) {
        if (side != 0 && nowMs - lastSideMs > HOLD_MS) side = 0
        val dtS = dtMs / 1_000.0
        val target = side * TURN_DEG
        val rate = if (side != 0) TURN_DEG / TURN_S else RETURN_DEG_S
        commandDeg += (target - commandDeg).coerceIn(-rate * dtS, rate * dtS)
        tremorDeg = TREMOR_DEG * rng.nextGaussian()
        val heading = Math.toRadians(commandDeg + tremorDeg)
        x += WalkModel.WALK_MPS * dtS * sin(heading)
        y += WalkModel.WALK_MPS * dtS * cos(heading)
    }

    companion object {
        const val TURN_DEG = 25.0
        const val TURN_S = 0.4
        const val HOLD_MS = 1_200L
        const val RETURN_DEG_S = 20.0
        const val TREMOR_DEG = 2.0
    }
}
