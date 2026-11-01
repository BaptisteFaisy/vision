package dev.sam3vision.core.motion

import dev.sam3vision.core.predict.Shift

/**
 * Rotation caméra datée dans les unités du plan de luminance : [hint] le
 * déplacement gyro attendu (pixels luma, voir [GyroCompensation.expectedShift]),
 * [lumaWidth]×[lumaHeight] la grille luma (unités du [hint]), [dtMs] le délai
 * inter-frames couvert (> 0). C'est le pendant gyro de `FrameMotion` (côté
 * traverse) : les guetteurs véhicules le consomment quand le fond optique est
 * muet (pan rapide qui sature le bloc-matching) ou absent.
 */
data class GyroMotion(
    val hint: GyroHint,
    val lumaWidth: Int,
    val lumaHeight: Int,
    val dtMs: Long,
)

/**
 * Estimation fusionnée du mouvement de la caméra (flot optique du fond +
 * gyroscope) : chaque piste véhicule s'y compare avant de conclure — le
 * mouvement apparent moins l'ego-motion, c'est le mouvement propre.
 *
 * Ordre verrouillé (contrat PISTE4, `VehicleBackgroundTest`) : le fond visuel
 * utilisable décide seul, le gyro est ignoré ; le gyro ne prend le relais
 * qu'en repli, quand le bloc-matching est muet ou peu confiant (pan rapide).
 * Le gyro ne contredit donc jamais un fond sûr — et sans aucune source, le
 * doute ne parle jamais (ni mouvement tu, ni arrêt inventé).
 *
 * Sens conservateur : le gyro ne décote que la translation (une boîte qui
 * suit la rotation, c'est un arrêt compensé, pas un mouvement) — jamais la
 * croissance (le gyro mesure la rotation, pas l'avance/zoom : une boîte qui
 * gonfle sans fond prouvé annonce toujours, comme avant). Une voiture en
 * mouvement ne passe donc jamais pour arrêtée par le gyro.
 *
 * Pur JVM, zéro allocation hors résultat.
 */
object EgoMotion {

    /** Confiance minimale d'une source, reprise de `Shift.TRUST_MIN` (miroir du veto fond). */
    const val TRUST_MIN = Shift.TRUST_MIN

    /**
     * [StampedDelta] → [GyroHint] en pixels du plan luma ([focalPx] = focale de
     * ce plan, `LensSpec.focalPx(lumaWidth, lumaHeight)`), `null` sans avis :
     * delta absent, périmé ou excessif (mêmes garde-fous que
     * [GyroCompensation.residual]), focale inutilisable, ou [confidence] non
     * finie. [confidence] = 1 par défaut (rotation mesurée proprement).
     */
    fun hintFor(stamped: StampedDelta?, focalPx: Float, confidence: Float = 1f): GyroHint? {
        if (stamped == null) return null
        if (!confidence.isFinite()) return null
        if (stamped.ageMs < 0L || stamped.ageMs > GyroCompensation.MAX_AGE_MS) return null
        val delta = stamped.delta
        if (!delta.dYawRad.isFinite() || !delta.dPitchRad.isFinite()) return null
        if (kotlin.math.abs(delta.dYawRad) > GyroCompensation.MAX_DELTA_RAD) return null
        if (kotlin.math.abs(delta.dPitchRad) > GyroCompensation.MAX_DELTA_RAD) return null
        val expected = GyroCompensation.expectedShift(delta, focalPx) ?: return null
        return GyroHint(expected.dxPx, expected.dyPx, confidence)
    }

    /**
     * Échantillon ego-motion décisionnel : le fond visuel s'il est utilisable
     * (confiant et fini), sinon le repli gyro (rotation pure, pas de zoom),
     * sinon `null` (doute). Le gyro ne contredit jamais un fond sûr.
     */
    fun fusedSample(global: MotionSample?, gyro: GyroHint?): MotionSample? =
        global?.takeIf(::visualUsable) ?: gyro?.toSample()

    /** Fond visuel exploitable : confiance et valeurs finies (contrat du veto fond). */
    fun visualUsable(sample: MotionSample): Boolean =
        sample.confidence.isFinite() && sample.confidence >= TRUST_MIN &&
            sample.dx.isFinite() && sample.dy.isFinite() && sample.scale.isFinite()
}
