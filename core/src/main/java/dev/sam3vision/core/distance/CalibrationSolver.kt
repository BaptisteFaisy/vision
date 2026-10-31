package dev.sam3vision.core.distance

import kotlin.math.atan

/**
 * Résultat d'un étalonnage guidé : le champ de vision mesuré et son incertitude.
 *
 * @property fovDeg champ horizontal mesuré, en degrés.
 * @property toleranceDeg ± degrés (quantification ±1 px sur la boîte + 0,5 % de
 * ruban) : un étalonnage à 340 px vaut ±0,5°, à 40 px ±1,7° — viser grand.
 */
data class Calibration(val fovDeg: Float, val toleranceDeg: Float)

/**
 * Étalonnage guidé du champ de vision : une personne de taille connue ([heightMeters],
 * au cm près) à une distance mesurée au mètre ruban ([distanceMeters]), le téléphone
 * lit sa hauteur en pixels ([boxHeightPx]) sur une image de [imageWidthPx] et en déduit
 * la focale (`f = h × D / H`) puis le champ (`2·atan(W / 2f)`).
 *
 * C'est le seul réglage qui annule le biais systématique de focale (capteur ±2-3 %,
 * hauteur d'épaule supposée) : mesuré une fois pour cet appareil et cet utilisateur,
 * il remplace la lecture capteur dans les Réglages. Pur JVM, sans Android.
 */
object CalibrationSolver {

    /** Hauteur de boîte minimale : en dessous, ±1 px pèse plus de 5 %. */
    const val MIN_BOX_PX = 20f

    /** Tailles et distances plausibles pour une personne (garde de saisie). */
    const val MIN_HEIGHT_M = 0.5f
    const val MAX_HEIGHT_M = 2.5f
    const val MIN_DISTANCE_M = 0.5f
    const val MAX_DISTANCE_M = 10f

    /** Part du mètre ruban dans l'incertitude relative (±0,5 %). */
    const val TAPE_FRACTION = 0.005f

    /**
     * Champ mesuré, `null` si une entrée est incohérente ou si le champ sort des
     * bornes [LensSpec] (saisie ou visée fausse — pas de valeur inventée).
     */
    fun solve(
        heightMeters: Float,
        distanceMeters: Float,
        boxHeightPx: Float,
        imageWidthPx: Int,
    ): Calibration? {
        if (!heightMeters.isFinite() || heightMeters !in MIN_HEIGHT_M..MAX_HEIGHT_M) return null
        if (!distanceMeters.isFinite() || distanceMeters !in MIN_DISTANCE_M..MAX_DISTANCE_M) return null
        if (!boxHeightPx.isFinite() || boxHeightPx < MIN_BOX_PX) return null
        if (imageWidthPx <= 0) return null
        val focalPx = boxHeightPx * distanceMeters / heightMeters
        if (!focalPx.isFinite() || focalPx <= 0f) return null
        val fovDeg = Math.toDegrees(2.0 * atan(imageWidthPx / 2.0 / focalPx)).toFloat()
        if (!fovDeg.isFinite() || fovDeg !in LensSpec.FOV_MIN..LensSpec.FOV_MAX) return null
        val tolerance = fovDeg * (1f / boxHeightPx + TAPE_FRACTION)
        return Calibration(fovDeg, tolerance)
    }
}
