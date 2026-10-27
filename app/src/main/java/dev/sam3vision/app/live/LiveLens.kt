package dev.sam3vision.app.live

import dev.sam3vision.core.distance.LensSpec

/**
 * Calibrage retenu pour les distances, extrait de `LiveViewModel` (limite de 500 lignes) :
 * le champ de vision réglé à la main prime, sinon celui lu sur le capteur, sinon le
 * grand-angle de repli.
 */
internal fun chosenLens(forcedFovDeg: Float, sensor: LensSpec?): LensSpec {
    if (forcedFovDeg > 0f) return LensSpec.fromHorizontalFov(forcedFovDeg)
    return sensor ?: LensSpec.Default
}