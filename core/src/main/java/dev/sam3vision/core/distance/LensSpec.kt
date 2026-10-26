package dev.sam3vision.core.distance

import kotlin.math.atan
import kotlin.math.tan

/**
 * Calibrage optique de la caméra, en **champs de vision** (degrés) du capteur.
 *
 * Le sténopé n'a besoin que d'une chose : la focale **en pixels** de l'image
 * analysée. Elle se déduit du champ de vision de l'axe qui porte la *largeur* de
 * cette image — le direct redresse chaque frame, une image portrait livre donc
 * en largeur l'axe *vertical* du capteur ([sensorLandscape] dit dans quel sens
 * le capteur est natif).
 */
data class LensSpec(
    val horizontalFovDeg: Float,
    val verticalFovDeg: Float,
    val sensorLandscape: Boolean = true,
) {

    /** Vrai si les deux angles sont plausibles pour une caméra de téléphone. */
    val usable: Boolean
        get() = horizontalFovDeg in FOV_MIN..FOV_MAX && verticalFovDeg in FOV_MIN..FOV_MAX

    /** Champ de vision porté par la largeur d'une image `[width]`×`[height]`. */
    fun widthFovDeg(width: Int, height: Int): Float {
        val imageLandscape = width >= height
        return if (imageLandscape == sensorLandscape) horizontalFovDeg else verticalFovDeg
    }

    /**
     * Focale en pixels pour une image `[width]`×`[height]` : `largeur / 2·tan(fov/2)`.
     * `0f` si le calibrage est inutilisable (angle nul, champ hors bornes, image vide).
     */
    fun focalPx(width: Int, height: Int): Float {
        if (!usable || width <= 0 || height <= 0) return 0f
        val halfTangent = tan(Math.toRadians(widthFovDeg(width, height) / 2.0))
        return if (halfTangent <= 1e-6) 0f else (width / 2.0 / halfTangent).toFloat()
    }

    companion object {
        /** En deçà, ce n'est plus une caméra (téléobjectif extrême). */
        const val FOV_MIN = 15f

        /** Au-delà, c'est un fish-eye : la projection sténopé n'y tient plus. */
        const val FOV_MAX = 130f

        /** Grand-angle de téléphone typique, capteur 4:3. */
        const val DEFAULT_H_FOV_DEG = 67f

        /** Repli quand le capteur ne dit rien et que l'utilisateur n'a rien réglé. */
        val Default: LensSpec = fromHorizontalFov(DEFAULT_H_FOV_DEG)

        /**
         * Calibrage déduit d'un seul angle horizontal (réglage utilisateur), pour un
         * capteur d'aspect [aspect] (4:3 par défaut) : le champ vertical suit la même
         * focale que l'horizontal.
         */
        fun fromHorizontalFov(deg: Float, aspect: Float = 4f / 3f): LensSpec {
            val safeAspect = if (aspect > 0f) aspect else 4f / 3f
            val halfTangent = tan(Math.toRadians(deg / 2.0)) / safeAspect
            val vertical = Math.toDegrees(2.0 * atan(halfTangent)).toFloat()
            return LensSpec(deg, vertical)
        }

        /**
         * Calibrage des Réglages : le champ horizontal forcé par l'utilisateur prime
         * (`cameraFovDeg > 0`), sinon le grand-angle de repli. Un seul endroit à changer
         * pour les deux écrans qui mesurent des distances (Direct et page caméra iSee).
         */
        fun fromSettings(forcedFovDeg: Float): LensSpec =
            if (forcedFovDeg in FOV_MIN..FOV_MAX) fromHorizontalFov(forcedFovDeg) else Default

        /** Calibrage à partir d'une focale (mm) et d'un capteur (mm), `null` si incohérent. */
        fun fromSensor(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): LensSpec? {
            if (focalMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
            val horizontal = Math.toDegrees(2.0 * atan(sensorWidthMm / 2.0 / focalMm)).toFloat()
            val vertical = Math.toDegrees(2.0 * atan(sensorHeightMm / 2.0 / focalMm)).toFloat()
            return LensSpec(horizontal, vertical, sensorLandscape = sensorWidthMm >= sensorHeightMm)
        }
    }
}