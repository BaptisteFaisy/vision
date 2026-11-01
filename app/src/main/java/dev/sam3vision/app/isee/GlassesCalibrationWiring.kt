package dev.sam3vision.app.isee

import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.live.chooseLens
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Bascule du calibrage distances (téléphone ↔ lunettes) sans toucher aux
 * sessions : le lens et la pose changent, les pistes calibrées sur l'ancien
 * capteur sont oubliées. Un lens inutilisable garde l'ancien (voir `setLens`).
 */
internal fun LiveProbe.setCalibration(lens: LensSpec, pose: CameraPose? = null) {
    distances.setLens(lens)
    distances.pose = pose
    distances.reset()
}

/**
 * Suit la source des frames : lunettes en direct → calibrage lunettes + pose
 * yeux ; retour téléphone → capteur + pose IMU. [onGlasses]/[onPhone] sont les
 * actions pose (`pitch.stop()`/`pitch.start()` en prod), injectées car le
 * `PitchMeter` exige un `Context` Android, absent des tests JVM.
 * `configureFrom` converge vers le même choix à la prochaine reconfiguration,
 * donc les deux chemins ne divergent jamais.
 */
internal fun LiveProbe.observeGlassesLive(
    scope: CoroutineScope,
    settings: () -> Settings,
    phoneLens: () -> LensSpec?,
    onGlasses: () -> Unit,
    onPhone: () -> Unit,
) {
    scope.launch {
        // `live` est un StateFlow conflated : une valeur répétée ne réémet pas.
        GlassesVideoLink.live.collect { live ->
            if (live) {
                onGlasses()
                val chosen = chooseLens(settings().glassesFovDeg, phoneLens(), fromGlasses = true)
                setCalibration(chosen.spec, CameraPose.Glasses)
            } else {
                val chosen = chooseLens(settings().cameraFovDeg, phoneLens())
                setCalibration(chosen.spec, null)
                onPhone()
            }
        }
    }
}
