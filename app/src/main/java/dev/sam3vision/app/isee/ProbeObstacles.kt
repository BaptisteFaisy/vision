package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.VehicleStateCue
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
 * Les signaux des guetteurs core sont lus tels quels (autre mission).
 *
 * [voice], [playerIdle], [safety] et le mutex de parole douce sont le
 * câblage historique, gardés pour la bascule : la parole passe par le bus,
 * câblé une fois au démarrage — le parent les retire quand tous les
 * producteurs postent au bus.
 */
internal fun LiveProbe.observeObstacles(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
    safety: SafetyVoice,
    vehicle: VehicleStateWatcher = VehicleStateWatcher(),
    curb: CurbWatcher = CurbWatcher(),
    hazard: HazardWatcher = HazardWatcher(),
): ObstacleWatchers {
    val watchers = ObstacleWatchers(vehicle, curb, hazard)
    val softSpeech = Mutex()
    scope.launch {
        state.collect { st ->
            obstacleFrame(st, scope, voice, playerIdle, language, safety, watchers, softSpeech)
        }
    }
    return watchers
}

/**
 * Une frame d'obstacles : les trois signaux relus dans l'état (lectures de
 * distance + dimensions, déjà publiées par frame), convertis en vœux
 * ([vehicleWish], [hazardWish], [curbWish] — mot imminent humain en clip
 * immédiat, niveau 2), arbitrés ([decideFrame]) puis proposés au bus
 * ([offer]). `collect` séquentiel : une alerte confirmée ne s'annule pas.
 */
@Suppress("UNUSED_PARAMETER")
internal fun obstacleFrame(
    st: LiveProbeState,
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
    safety: SafetyVoice,
    watchers: ObstacleWatchers,
    softSpeech: Mutex,
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
    val hazardCue = watchers.hazard.onSignal(HazardSignal.of(readings, st.width, st.height), nowMs, lang)
    offer(
        decideFrame(
            listOf(
                vehicleWish(vehicleCue),
                hazardWish(hazardCue, st.crossingActive),
                curbWish(curbCue, st.crossingActive),
            ),
        ),
        nowMs,
    )
}
