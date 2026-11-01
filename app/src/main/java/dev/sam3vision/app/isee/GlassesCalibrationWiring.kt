package dev.sam3vision.app.isee

import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.live.GlassesMireStore
import dev.sam3vision.app.live.chooseLens
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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
 * donc les deux chemins ne divergent jamais. [glassesLens] fournit la mire
 * persistée (`null` = jamais posée, le repli estimé s'applique).
 */
/**
 * Bascule lunettes-live de la sonde : le tangage téléphone ne vise pas où visent
 * les lunettes, donc sans pose tête ([headPose] `null`) la branche plan-de-sol
 * est coupée explicitement — l'estimation tombe sur le sténopé seul
 * (coupe-circuit D3). Avec une pose tête DAT Motion, la branche rouvre **sur
 * la tête** : le sol est mesuré aux yeux, pas à la poche. Retour téléphone :
 * la branche rouvre (la pose IMU reprend si le tangage tourne, sinon `null` =
 * sténopé seul).
 */
fun LiveProbe.setGlassesLive(live: Boolean, headPose: CameraPose? = null) {
    if (!live) {
        distances.groundPlaneEnabled = true
        android.util.Log.i("GlassesBridge", "plan de sol rouvert (téléphone)")
        return
    }
    val pose = headPose?.takeIf { it.isValid }
    distances.pose = pose
    distances.groundPlaneEnabled = pose != null
    android.util.Log.i(
        "GlassesBridge",
        "plan de sol " + if (pose != null) "lunettes-live sur pose tête" else "coupé (lunettes-live, sténopé seul)",
    )
}

/**
 * Suit la pose tête DAT Motion : chaque émission, si le direct vit, la pose
 * (fraîche) rouvre la branche sol, son absence la recoupe — [setGlassesLive]
 * reste le seul écrivain de la bascule. Hors direct, les émissions sont
 * ignorées (la voie téléphone garde la main).
 */
internal fun LiveProbe.observeHeadPose(scope: CoroutineScope) {
    scope.launch {
        GlassesVideoLink.headPose.collect { pose ->
            if (!GlassesVideoLink.live.value) return@collect
            val fresh = pose?.takeIf { GlassesVideoLink.freshHeadPose() === it }
            setGlassesLive(true, fresh)
        }
    }
}

/** Vrai quand la branche sol est coupée (lunettes-live) : hook UI, sans état. */
val LiveProbe.groundDegraded: Boolean get() = !distances.groundPlaneEnabled

internal fun LiveProbe.observeGlassesLive(
    scope: CoroutineScope,
    settings: () -> Settings,
    phoneLens: () -> LensSpec?,
    onGlasses: () -> Unit,
    onPhone: () -> Unit,
    glassesLens: () -> LensSpec? = { null },
) {
    scope.launch {
        // `live` est un StateFlow conflated : une valeur répétée ne réémet pas.
        // `mireVersion` force la relecture (Save/Clear mire → stack reposé chaud).
        combine(GlassesVideoLink.live, GlassesMireStore.mireVersion) { live, _ -> live }
            .collect { live ->
            if (live) {
                onGlasses()
                val chosen = chooseLens(settings().glassesFovDeg, phoneLens(), glassesLens(), fromGlasses = true)
                // Focale flux pour le pont E5 (même étage D1) : recadrée par photo.
                GlassesVideoLink.glassesStreamLens = chosen.spec
                setCalibration(chosen.spec, CameraPose.Glasses)
            } else {
                GlassesVideoLink.glassesStreamLens = null
                val chosen = chooseLens(settings().cameraFovDeg, phoneLens())
                setCalibration(chosen.spec, null)
                onPhone()
            }
        }
    }
}
