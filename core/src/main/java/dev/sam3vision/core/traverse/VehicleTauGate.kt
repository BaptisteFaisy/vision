package dev.sam3vision.core.traverse

import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.predict.EgoCompensator
import dev.sam3vision.core.predict.MotionModel

/** Croissances brutes exigées pour le tau (statique tenu, retrait casse) : flicker ≤ 4 (G1w). */
internal const val TAU_MIN_RUN = 5

/** Nourrit une piste : run +1 par croissance, tenu sur frame statique, zéro sinon. */
internal fun feedTrackRun(track: VehicleApproach.Track, box: Box, atMs: Long, maxSpeed: Float) {
    val outcome = track.motion.observe(box, atMs, maxSpeed)
    if (outcome == MotionModel.Outcome.JUMPED) {
        // Saut = nouvel objet (coupe de scène, changement d'id) : le run ET
        // l'armement meurent (un tau armé sur S2 ne tire plus sur S1).
        track.growthRun = 0
        track.tauArmed = false
        return
    }
    val raw = track.motion.lastRawWidth
    track.growthRun = if (outcome == MotionModel.Outcome.STALE) 0
        else if (raw > 0f) track.growthRun + 1 else if (raw < 0f) 0 else track.growthRun
}

/** Tau armé au run-5, tenu sur vélocité (flicker : runs ≤ 4, jamais armé). */
internal fun tauEligible(track: VehicleApproach.Track, frameWidth: Int, width: Float, global: MotionSample?, globalDtMs: Long): Boolean {
    val tauOk = track.motion.velocity.width > frameWidth * VehicleApproach.MIN_GROWTH_RATIO &&
        !EgoCompensator.discountGrowth(track.motion.velocity.width / width, global, globalDtMs).discounted
    if (!tauOk) track.tauArmed = false else if (track.growthRun >= TAU_MIN_RUN) track.tauArmed = true
    return track.tauArmed && tauOk
}
