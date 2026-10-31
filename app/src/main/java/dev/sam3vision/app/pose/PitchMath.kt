package dev.sam3vision.app.pose

import dev.sam3vision.core.distance.CameraPose
import kotlin.math.PI

/** Pourquoi la pose manque : un `null` de pose dit toujours sa raison (voir [PoseOutcome]). */
enum class PoseIssue {
    /** Aucun échantillon IMU reçu (capteur absent, écoute coupée, démarrage). */
    NO_SAMPLE,

    /** Tangage non fini (échantillon IMU corrompu, jeté par [PitchMath.lowPass]). */
    BAD_PITCH,

    /** Hauteur hors main ou non finie (voir [CameraPose.isValid]). */
    BAD_HEIGHT,
}

/**
 * Pose complète ou raison de son absence : le repli sténopé seul n'est plus
 * silencieux — l'appelant sait pourquoi la branche sol est muette.
 */
sealed interface PoseOutcome {
    data class Valid(val pose: CameraPose) : PoseOutcome
    data class Invalid(val issue: PoseIssue) : PoseOutcome
}

/**
 * Tangage filtré + chauffe du passe-bas : le premier échantillon est pris brut
 * (pas de passé à mélanger), le filtre n'est stabilisé qu'après [PitchMath.SETTLING_SAMPLES].
 */
data class FilteredPitch(val pitchRad: Float, val samples: Int) {
    /** Vrai quand le passe-bas a assez mélangé pour être cru sur parole. */
    val settled: Boolean get() = samples >= PitchMath.SETTLING_SAMPLES
}

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

    /** Échantillons pour un passe-bas stabilisé (voir [FilteredPitch.settled]). */
    const val SETTLING_SAMPLES = 5

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
     * Passe-bas avec chauffe : même mélange que [lowPass], mais le compte
     * d'échantillons voyage ([FilteredPitch]) au lieu d'un taux cru dès la
     * première frame. Un échantillon non fini est jeté (précédent gardé, 0 si aucun).
     */
    fun lowPass(previous: FilteredPitch?, sample: Float, alpha: Float = ALPHA): FilteredPitch {
        if (!sample.isFinite()) return previous ?: FilteredPitch(0f, 0)
        val prev = previous ?: return FilteredPitch(sample, 1)
        val rate = alpha.coerceIn(0f, 1f)
        return FilteredPitch(prev.pitchRad + rate * (sample - prev.pitchRad), prev.samples + 1)
    }

    /**
     * Pose complète et sa raison d'absence : `null` (repli sténopé seul) quand le
     * tangage manque ([PoseIssue.NO_SAMPLE], [PoseIssue.BAD_PITCH]) ou que la pose
     * est inexploitable ([PoseIssue.BAD_HEIGHT] — voir [CameraPose]).
     */
    fun poseOutcome(heightMeters: Float, pitchRad: Float?): PoseOutcome {
        if (pitchRad == null) return PoseOutcome.Invalid(PoseIssue.NO_SAMPLE)
        if (!pitchRad.isFinite()) return PoseOutcome.Invalid(PoseIssue.BAD_PITCH)
        val pose = CameraPose(heightMeters, pitchRad)
        return if (pose.isValid) PoseOutcome.Valid(pose)
        else PoseOutcome.Invalid(PoseIssue.BAD_HEIGHT)
    }

    /**
     * Pose complète, ou `null` (repli sténopé seul) quand le tangage manque ou que la
     * pose est inexploitable (hauteur hors main, angle non fini — voir [CameraPose]).
     */
    fun poseOrNull(heightMeters: Float, pitchRad: Float?): CameraPose? =
        (poseOutcome(heightMeters, pitchRad) as? PoseOutcome.Valid)?.pose
}
