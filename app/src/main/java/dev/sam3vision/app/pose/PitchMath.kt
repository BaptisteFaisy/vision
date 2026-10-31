package dev.sam3vision.app.pose

import dev.sam3vision.core.distance.CameraPose
import kotlin.math.PI

/**
 * Maths pures du tangage IMU : orientation → [CameraPose], sans Android ni capteur.
 *
 * Convention (voir `docs/POSE-IMU.md`) : `getOrientation[1]` vaut −π/2 quand le
 * téléphone est tenu vertical (portrait, écran face à l'utilisateur, axe optique
 * horizontal) et 0 quand il est à plat écran vers le ciel (caméra plein vers le
 * sol). Le tangage [CameraPose] (`+` = l'axe optique pique) est donc ce pitch
 * décalé de +π/2 — exact pour le hochement autour de l'axe X de l'écran, avec le
 * téléphone tenu vertical (roulis ≈ 0).
 */
object PitchMath {

    /** Passe-bas du tangage : 0,2 = réactif sans tremblé (≈ 5 échantillons). */
    const val ALPHA = 0.2f

    /**
     * Tangage [CameraPose] (rad, `+` = pique vers le bas) depuis `getOrientation[1]`.
     * Borné à ±π/2 ; un échantillon non fini traverse tel quel (ignoré par [lowPass]).
     */
    fun pitchRadFromOrientation(orientationPitch: Float): Float {
        val halfPi = PI.toFloat() / 2f
        return (orientationPitch + halfPi).coerceIn(-halfPi, halfPi)
    }

    /**
     * Passe-bas : premier échantillon pris tel quel, puis mélange au taux [alpha]
     * (borné 0..1). Un échantillon non fini est jeté (le précédent est gardé, 0 si aucun).
     */
    fun lowPass(previous: Float?, sample: Float, alpha: Float = ALPHA): Float {
        if (!sample.isFinite()) return previous ?: 0f
        val prev = previous ?: return sample
        val rate = alpha.coerceIn(0f, 1f)
        return prev + rate * (sample - prev)
    }

    /**
     * Pose complète, ou `null` (repli sténopé seul) quand le tangage manque ou que la
     * pose est inexploitable (hauteur hors main, angle non fini — voir [CameraPose]).
     */
    fun poseOrNull(heightMeters: Float, pitchRad: Float?): CameraPose? {
        if (pitchRad == null || !pitchRad.isFinite()) return null
        val pose = CameraPose(heightMeters, pitchRad)
        return pose.takeIf { it.isValid }
    }
}
