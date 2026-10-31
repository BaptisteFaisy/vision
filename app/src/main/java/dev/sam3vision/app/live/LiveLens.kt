package dev.sam3vision.app.live

import dev.sam3vision.core.distance.LensSpec

/**
 * Calibrage retenu pour les distances, extrait de `LiveViewModel` (limite de 500 lignes) :
 * le champ de vision réglé à la main prime, sinon celui lu sur le capteur, sinon le
 * grand-angle de repli.
 *
 * [fromGlasses] = la frame mesurée vient des lunettes : le calibrage du téléphone ne
 * vaut plus, c'est [glasses] qui s'applique (`null` = pas encore calibré sur mire, le
 * repli grand-angle s'applique en attendant — voir le protocole mire de
 * `docs/LUNETTES-RECETTE-TEL.md`). Par défaut (téléphone), le routage est inchangé.
 */
internal fun chosenLens(
    forcedFovDeg: Float,
    sensor: LensSpec?,
    glasses: LensSpec? = null,
    fromGlasses: Boolean = false,
): LensSpec {
    if (forcedFovDeg > 0f) return LensSpec.fromHorizontalFov(forcedFovDeg)
    if (fromGlasses) return glasses ?: LensSpec.Default
    return sensor ?: LensSpec.Default
}