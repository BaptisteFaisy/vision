package dev.sam3vision.core.distance

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Pose de la caméra pour la mesure au sol : hauteur du capteur et inclinaison.
 *
 * [pitchRad] est positif quand l'axe optique **pique vers le bas** (téléphone incliné
 * vers ses pieds) : l'horizon remonte alors au-dessus du centre de l'image. Une hauteur
 * hors bornes ou un angle non fini rendent la pose inexploitable — on ne mesure pas avec.
 */
data class CameraPose(
    val heightMeters: Float,
    val pitchRad: Float = 0f,
) {

    /** Vrai si la hauteur est tenable à la main et que les deux nombres sont mesurables. */
    val isValid: Boolean
        get() = heightMeters.isFinite() && pitchRad.isFinite() &&
            heightMeters in MIN_HEIGHT_M..MAX_HEIGHT_M

    companion object {
        /** Sous 20 cm, ce n'est plus un téléphone tenu à la main. */
        const val MIN_HEIGHT_M = 0.2f

        /** Au-delà de 5 m, la hauteur vient d'un support (perche, drone), pas d'une main. */
        const val MAX_HEIGHT_M = 5f

        /** Hauteur d'épaule : le téléphone à hauteur de poitrine. */
        const val DEFAULT_HEIGHT_M = 1.5f

        /** Pose de repli : aucune inclinaison connue, hauteur d'épaule. */
        val Default = CameraPose(DEFAULT_HEIGHT_M)
    }
}

/**
 * Un point du sol sous l'objectif : sa profondeur devant la caméra, son écart latéral,
 * et la distance réelle qui les sépare ([slantMeters]).
 */
data class GroundPoint(
    val forwardMeters: Float,
    val lateralMeters: Float,
    val slantMeters: Float,
    val depressionRad: Float,
)

/**
 * Plan de sol : la ligne des **pieds** d'un objet posé donne sa distance, sans rien savoir
 * de sa taille — seulement la hauteur de la caméra et son inclinaison.
 *
 * `y = cy + f·tan(atan(h_cam / d) − pitch)` ([rowAt]) : la seconde formule de la lane L19,
 * complémentaire du sténopé ([Pinhole]), qui lui demande une hauteur de classe connue
 * ([KnownSizes]). Un objet **coupé** n'a plus de ligne des pieds fiable — comme il n'a plus
 * de hauteur pour le sténopé — et les gardes rendent [null] plutôt qu'un chiffre faux, avec
 * les mêmes bornes de plausibilité ([Pinhole.MIN_METERS], [Pinhole.MAX_METERS]).
 *
 * Les coordonnées sont en pixels de l'image **analysée** (jamais d'une échelle réduite) et
 * les angles en radians. Le centre optique n'est pas toujours le centre de l'image :
 * [principalX] et [principalY] sont ceux de la frame courante.
 */
object GroundPlane {

    /**
     * Ligne de l'horizon : `cy − f·tan(pitch)`. Au centre optique quand la caméra ne pique
     * pas ; au-dessus dès qu'elle pique, jusqu'à sortir du cadre.
     */
    fun horizonRow(pose: CameraPose, focalPx: Float, principalY: Float): Float =
        principalY - focalPx * tan(pose.pitchRad)

    /**
     * Dépression de la ligne [row] sous l'horizon, en radians : nulle sur l'horizon
     * (le sol à l'infini), négative au-dessus. C'est l'angle du rayon des pieds mesuré
     * depuis l'horizontale — la grandeur que la formule du plan de sol inverse.
     */
    fun depressionRad(row: Float, pose: CameraPose, focalPx: Float, principalY: Float): Float =
        atan((row - principalY) / focalPx) + pose.pitchRad

    /**
     * Ligne de l'image où tomberait un objet posé à [forwardMeters] devant la caméra.
     * [null] si la pose, la distance ou le calibrage ne permettent pas de la calculer ; la
     * ligne peut sortir du cadre (objet plus près que le bord bas), c'est à l'appelant d'en juger.
     */
    fun rowAt(
        forwardMeters: Float,
        pose: CameraPose,
        focalPx: Float,
        principalY: Float,
    ): Float? {
        if (!pose.isValid || !isUsable(focalPx) || !forwardMeters.isFinite() || forwardMeters <= 0f) return null
        val row = principalY + focalPx * tan(atan(pose.heightMeters / forwardMeters) - pose.pitchRad)
        return row.takeIf { it.isFinite() }
    }

    /**
     * Profondeur (m) d'un objet dont la ligne des pieds est [footRow] : `d = h_cam / tan(θ)`
     * avec `θ = atan((footRow − cy) / f) + pitch` ([depressionRad]).
     *
     * [null] — plutôt qu'un chiffre — si la ligne sort du cadre `[0, imageHeight]`, si elle est
     * **au-dessus de l'horizon** (θ ≤ 0 : ce n'est pas le sol) ou si le résultat tombe hors des
     * bornes de plausibilité du sténopé.
     */
    fun forwardMeters(
        footRow: Float,
        pose: CameraPose,
        focalPx: Float,
        principalY: Float,
        imageHeight: Int,
    ): Float? {
        if (!pose.isValid || !isUsable(focalPx) || imageHeight <= 0) return null
        if (!footRow.isFinite() || footRow < 0f || footRow > imageHeight) return null
        val depression = depressionRad(footRow, pose, focalPx, principalY)
        if (!depression.isFinite() || depression <= 0f) return null
        val meters = pose.heightMeters / tan(depression)
        return meters.takeIf { it.isFinite() && it in Pinhole.MIN_METERS..Pinhole.MAX_METERS }
    }

    /**
     * Écart latéral (m) du centre [centerX] d'une boîte, mesuré à la profondeur
     * [forwardMeters] : `X = d·(centerX − cx) / f`. Négatif à gauche du centre optique ;
     * [null] si la géométrie ne permet pas de le calculer.
     */
    fun lateralMeters(centerX: Float, forwardMeters: Float, focalPx: Float, principalX: Float): Float? {
        if (!isUsable(focalPx) || !centerX.isFinite()) return null
        if (!forwardMeters.isFinite() || forwardMeters <= 0f) return null
        return forwardMeters * (centerX - principalX) / focalPx
    }

    /**
     * Le point du sol sous l'objectif, complet : mêmes gardes que [forwardMeters], plus l'écart
     * latéral et la distance réelle ([GroundPoint.slantMeters], l'hypoténuse des deux).
     */
    fun pointOf(
        footRow: Float,
        centerX: Float,
        pose: CameraPose,
        focalPx: Float,
        principalX: Float,
        principalY: Float,
        imageHeight: Int,
    ): GroundPoint? {
        val forward = forwardMeters(footRow, pose, focalPx, principalY, imageHeight) ?: return null
        val lateral = lateralMeters(centerX, forward, focalPx, principalX) ?: return null
        return GroundPoint(
            forwardMeters = forward,
            lateralMeters = lateral,
            slantMeters = hypot(forward, lateral),
            depressionRad = depressionRad(footRow, pose, focalPx, principalY),
        )
    }

    /** Focale utilisable : finie et strictement positive. */
    private fun isUsable(focalPx: Float): Boolean = focalPx.isFinite() && focalPx > 0f
}