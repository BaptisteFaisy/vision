package dev.sam3vision.core.motion

import dev.sam3vision.core.predict.Shift

/**
 * Estimation du mouvement de la caméra (flot optique du fond) : chaque piste
 * véhicule s'y compare avant de conclure — le mouvement apparent moins
 * l'ego-motion, c'est le mouvement propre.
 *
 * Sans source visuelle utilisable, le doute ne parle jamais (ni mouvement tu,
 * ni arrêt inventé). Pur JVM, zéro allocation hors résultat.
 */
object EgoMotion {

    /** Confiance minimale d'une source, reprise de `Shift.TRUST_MIN` (miroir du veto fond). */
    const val TRUST_MIN = Shift.TRUST_MIN

    /** Fond visuel exploitable : confiance et valeurs finies (contrat du veto fond). */
    fun visualUsable(sample: MotionSample): Boolean =
        sample.confidence.isFinite() && sample.confidence >= TRUST_MIN &&
            sample.dx.isFinite() && sample.dy.isFinite() && sample.scale.isFinite()
}
