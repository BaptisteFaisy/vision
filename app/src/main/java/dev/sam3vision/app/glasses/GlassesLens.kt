package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DeviceType
import dev.sam3vision.core.distance.LensSpec

/**
 * Calibrage optique des lunettes pour les distances iSee.
 *
 * Le SDK DAT 1.0.0 n'expose ni intrinsics ni FOV (R3) : le calibrage vient
 * d'une table par modèle (R4, mesures labo DXOMARK sur mire), lue via
 * `Device.deviceType`. Les valeurs sont des champs **capteur** (12 MP 3:4) :
 *
 * - `RAYBAN_META` (gen1 + gen2, même capteur 12 MP 3024×4032) → 66° (labo) ;
 * - `RAYBAN_META_OPTICS` → 66° (même optique présumée) ;
 * - `OAKLEY_META_HSTN` → 70° (labo) ;
 * - `OAKLEY_META_VANGUARD` → 95° (dérivation validée FUP : marketing 122°
 *   confirmé 8+ sources, méthode prouvée à 1,1° sur HSTN labo, bande [90, 97],
 *   confirmation mire non bloquante, protocole dans docs/LUNETTES-VANGUARD.md) ;
 * - autres / `UNKNOWN` / sans SDK → 68° (défaut R4, milieu de [66, 70]).
 *
 * Le flux DAT HIGH 720×1280 est la fenêtre 9:16 centrale pleine hauteur du
 * capteur ([LensSpec.glassesStreamForSensorH]) : 68° → 53,7° × 83,9°.
 * Centre supposé centré, distorsion nulle (non mesurée, comme le téléphone
 * sans mire). La focale pixels est recalculée par `LensSpec.focalPx` aux
 * dimensions envoyées : le FOV angulaire ne change pas par réduction.
 */

/** Vanguard : 122° marketing → 94,5° (FUP : méthode validée, bande [90, 97]). */
internal const val VANGUARD_SENSOR_H_FOV_DEG = 95f

/** Champ capteur du modèle connecté, ou le repli 68° si non reconnu. */
internal fun glassesSensorH(deviceType: DeviceType?): Float = when (deviceType) {
    DeviceType.RAYBAN_META -> 66f
    DeviceType.RAYBAN_META_OPTICS -> 66f
    DeviceType.OAKLEY_META_HSTN -> 70f
    DeviceType.OAKLEY_META_VANGUARD -> VANGUARD_SENSOR_H_FOV_DEG
    DeviceType.META_GLASSES, DeviceType.META_RAYBAN_DISPLAY, DeviceType.UNKNOWN, null ->
        LensSpec.GLASSES_SENSOR_H_FOV_DEG
}

/** Calibrage du flux DAT HIGH du modèle donné (table capteur → fenêtre 9:16). */
internal fun glassesStreamLensFor(deviceType: DeviceType?): LensSpec =
    LensSpec.glassesStreamForSensorH(glassesSensorH(deviceType))

/**
 * Modèle de la paire connue du SDK, `null` sans SDK ni pairing (miroir de
 * `knownDeviceName` : jamais d'exception, `Wearables` non initialisé → `null`).
 */
internal fun connectedDeviceType(): DeviceType? = runCatching {
    Wearables.devices.value.firstNotNullOfOrNull { id ->
        Wearables.devicesMetadata[id]?.value?.deviceType
    }
}.getOrNull()

/**
 * Profil flux du modèle connecté, `null` sans modèle reconnu (mire ou repli
 * estimé alors — l'appelant garde son étage suivant, jamais un profil deviné).
 */
internal fun connectedGlassesStreamLens(): LensSpec? {
    val model = connectedDeviceType() ?: return null
    if (model == DeviceType.UNKNOWN) return null
    return glassesStreamLensFor(model)
}
