package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.VehicleStateCue
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Portée parlée du mouvement : SPEC v4, voitures en mouvement < 20 m. */
private const val MOVING_SPEAK_METERS = 20f

/**
 * Véhicule de la frame : **frames SAM seules** — la porte avance à chaque
 * compteur vu, Traversée ou non (une réémission sans nouvelle frame rejoue
 * des lectures figées et casserait la confirmation). En Traversée, rien :
 * le verdict couvre les voitures. Portée 20 m (SPEC v4, niveau 0).
 */
private fun vehicleCueOf(
    st: LiveProbeState,
    readings: List<DistanceReading>,
    watchers: ObstacleWatchers,
    nowMs: Long,
    lang: SpeechLanguage,
    motion: FrameMotion?,
): VehicleStateCue? {
    val freshFrame = st.frames != watchers.lastVehicleFrames
    watchers.lastVehicleFrames = st.frames
    if (!freshFrame || st.crossingActive) return null
    return watchers.vehicle.update(
        detectionsOf(readings, st.width, st.height), readings, nowMs, lang, motion,
        movingMeters = MOVING_SPEAK_METERS,
    )
        ?.also {
            logMotionTransition(
                watchers.vehicle.cameraMoved, watchers.vehicle.lastMotionVerdicts.values,
                st.width, motion?.dtMs ?: 0L,
            )
        }
}

/**
 * Les trois guetteurs obstacles (véhicules + bordure + mobilier) et le
 * dernier compteur de frames SAM vu par les véhicules (`-1` = aucun).
 */
internal class ObstacleWatchers(
    val vehicle: VehicleStateWatcher,
    val curb: CurbWatcher,
    val hazard: HazardWatcher,
) {
    /**
     * Dernier compteur de frames SAM vu par les véhicules : une réémission
     * sans nouvelle frame rejoue des lectures figées et casserait la
     * confirmation (garde reprise de l'ex-`observeVehicleState`).
     */
    var lastVehicleFrames: Int = -1

    /** Oublie les trois guetteurs et la porte frames (nouvelle séance). */
    fun reset() {
        vehicle.reset()
        curb.reset()
        hazard.reset()
        lastVehicleFrames = -1
    }
}

/**
 * Guetteur obstacles de la sonde, SPEC v4 : véhicules, bordure et mobilier
 * calculés sur la même frame, **un seul gagnant** ([decideFrame] : le plus
 * petit niveau — 0 voiture, 2 humain, 4 barrière, 5 bordure, 7 divers ;
 * 101 humain et 102 bordure en Traversée) proposé au bus ([offer] :
 * antirépétition puis [VoiceBus.post]). L'écrasé n'est jamais redit ; le
 * bus écrase entre producteurs (escaliers, grillage, trottoir, GPS…).
 */
internal fun LiveProbe.observeObstacles(
    scope: CoroutineScope,
    language: () -> SpeechLanguage,
    vehicle: VehicleStateWatcher = VehicleStateWatcher(),
    curb: CurbWatcher = CurbWatcher(),
    hazard: HazardWatcher = HazardWatcher(),
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
): ObstacleWatchers {
    val watchers = ObstacleWatchers(vehicle, curb, hazard)
    scope.launch {
        state.collect { st ->
            obstacleFrame(st, language, watchers, placeMode)
        }
    }
    return watchers
}

/**
 * Une frame d'obstacles : les trois signaux relus dans l'état (lectures de
 * distance + dimensions, déjà publiées par frame), convertis en vœux
 * ([vehicleWish], [hazardWish], [curbWish] — mot d'évitement continu en
 * clip immédiat, humain niveau 2, poteau niveau 7), arbitrés ([decideFrame])
 * puis proposés au bus
 * ([offer]). `collect` séquentiel : une alerte confirmée ne s'annule pas.
 */
internal fun obstacleFrame(
    st: LiveProbeState,
    language: () -> SpeechLanguage,
    watchers: ObstacleWatchers,
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
) {
    val readings = st.distanceLayer?.readings ?: return
    val nowMs = System.currentTimeMillis()
    val lang = language()
    val motion = ProbeBackgroundMotion.lastMotion
    val vehicleCue = vehicleCueOf(st, readings, watchers, nowMs, lang, motion)
    val curbSignal = CurbSignal.of(readings, st.width, st.height)
    val curbCue = if (st.crossingActive && curbSignal.tier != CurbTier.NEAR) null
    else watchers.curb.onSignal(curbSignal, nowMs, lang)?.takeIf { shouldSpeakCurb(it, st.crossingActive) }
    if (BuildConfig.DEBUG) android.util.Log.d("CurbVoice", curbVoiceLine(readings, curbSignal, curbCue?.phrase, st.crossingActive))
    val hazardSignal = HazardSignal.of(readings, st.width, st.height)
    val hazardCue = watchers.hazard.onSignal(hazardSignal, nowMs, lang)
    if (BuildConfig.DEBUG) android.util.Log.d("HazardVoice", hazardVoiceLine(readings, hazardSignal, hazardCue?.phrase))
    // Dedans : seule la personne parle (cran 2, le même que dehors) —
    // voitures, bordures, mobilier et 101/102 se taisent (le verdict 100,
    // lui, parle toujours en Traversée).
    val indoor = placeMode() == PictoPlaceMode.DEDANS
    offer(
        decideFrame(
            listOf(
                vehicleWish(vehicleCue).takeUnless { indoor },
                hazardWish(hazardCue, st.crossingActive)
                    ?.takeIf { !indoor || (!st.crossingActive && hazardCue?.kind == HazardKind.PERSON) },
                curbWish(curbCue, st.crossingActive).takeUnless { indoor },
            ),
        ),
        nowMs,
    )
}
