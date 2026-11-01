package dev.sam3vision.core.distance

import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Tangage tête depuis le quaternion fusionné DAT Motion : maths pures, sans
 * SDK ni Android (miroir de `PitchMath`, testé en JVM).
 *
 * Convention (assumption, à valider sur paire réelle, §8 PROTOCOLE) : repère
 * lunettes X droite / Y haut / Z vers le porteur, regard = −Z ; le tangage
 * [CameraPose] (`+` = l'axe optique pique) vaut l'angle du regard sous
 * l'horizontale. Si le labo montre le signe inverse, retourner [PITCH_SIGN].
 */
object HeadPoseMath {

    /** Signe du tangage : +1 sauf si le labo prouve le repère inverse. */
    const val PITCH_SIGN = 1f

    /**
     * Tangage [CameraPose] (rad, `+` = pique) depuis un quaternion `(x, y, z, w)`.
     * Borné à ±π/2 par construction (`asin`) ; `NaN` si non fini ou de norme
     * nulle (traverse comme `NaN`, ignoré par [poseOrNull]).
     */
    fun pitchRadFromQuaternion(x: Float, y: Float, z: Float, w: Float): Float {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return Float.NaN
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (!norm.isFinite() || norm <= 0f) return Float.NaN
        val nx = x / norm
        val ny = y / norm
        val nz = z / norm
        val nw = w / norm
        return PITCH_SIGN * asin((2f * (ny * nz - nw * nx)).coerceIn(-1f, 1f))
    }

    /**
     * Pose lunettes (hauteur des yeux) depuis un quaternion, ou `null` (repli
     * sténopé seul) quand le tangage manque ou que la pose est inexploitable
     * (miroir de `PitchMath.poseOrNull`, inatteignable depuis `core`).
     */
    fun poseOrNull(x: Float, y: Float, z: Float, w: Float): CameraPose? {
        val pitch = pitchRadFromQuaternion(x, y, z, w)
        if (!pitch.isFinite()) return null
        return CameraPose(CameraPose.GLASSES_HEIGHT_M, pitch).takeIf { it.isValid }
    }
}
