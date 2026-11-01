package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Les trois guetteurs ordonnés (véhicules + bordure + mobilier) et l'instant
 * de la dernière alerte de sécurité dite (`null` si aucune : le doux attend
 * en file pendant [SAFETY_SPEECH_MS] au lieu de chevaucher).
 */
internal class ObstacleWatchers(
    val vehicle: VehicleStateWatcher,
    val curb: CurbWatcher,
    val hazard: HazardWatcher,
) {
    /** Dernière alerte de sécurité dite, `null` si aucune. */
    var lastSafetyMs: Long? = null

    /**
     * Dernier compteur de frames SAM vu par les véhicules : une réémission
     * sans nouvelle frame rejoue des lectures figées et casserait la
     * confirmation (garde reprise de l'ex-`observeVehicleState`, perdue au
     * merge de la PR #690).
     */
    var lastVehicleFrames: Int = -1

    /** Oublie les trois guetteurs et l'alerte (nouvelle séance). */
    fun reset() {
        vehicle.reset()
        curb.reset()
        hazard.reset()
        lastSafetyMs = null
        lastVehicleFrames = -1
    }
}

/**
 * Guetteur obstacles de la sonde : véhicules, bordure et mobilier calculés
 * sur la même frame, parlés une fois dans l'ordre P1-P5 (voiture en mouvement
 * > bordure > poteau > barrière > reste) au lieu de se couper. La sécurité
 * fusionnée ([SafetyVoice], jamais de « Stop » hors Traversée) part aussitôt,
 * en direct ; le doux fusionné part en coroutine fille, sérialisé ([Mutex] :
 * jamais de chevauchement doux, jamais de frame bloquée par une synthèse) et
 * tenu en file pendant la sécurité ([safetyBusy]) — sauf le danger qui
 * s'approche (`urgent` véhicule : coupe, comme avant). Sans doux frais (hors
 * Traversée), la file est redite au silence ([ProximityVoice.flushPending]).
 * En Traversée, seul l'imminent bordure passe (sa voix rouge/vert est
 * propriétaire du son) et les guetteurs coupés n'avancent pas. Rend les
 * guetteurs pour le `reset()` de début de séance. `collect` séquentiel : une
 * alerte confirmée ne s'annule pas.
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
 * distance + dimensions, déjà publiées par frame), arbitrés ([decideObstacles])
 * puis parlés — sécurité aussitôt, doux en fille sérialisée, file au silence.
 */
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
    // Frames SAM seules pour les véhicules (ex-`observeVehicleState`) : la
    // porte avance à chaque compteur vu, Traversée ou non.
    val freshFrame = st.frames != watchers.lastVehicleFrames
    watchers.lastVehicleFrames = st.frames
    val vehicleCue = if (!freshFrame || st.crossingActive) null else watchers.vehicle.update(
        detectionsOf(readings, st.width, st.height), readings, nowMs, lang, motion)
        ?.also { logMotionTransition(watchers.vehicle.cameraMoved, watchers.vehicle.lastMotionVerdicts.values, st.width, motion?.dtMs ?: 0L) }
    val curbSignal = CurbSignal.of(readings, st.width, st.height)
    val curbCue = if (st.crossingActive && curbSignal.tier != CurbTier.NEAR) null
    else watchers.curb.onSignal(curbSignal, nowMs, lang)?.takeIf { shouldSpeakCurb(it, st.crossingActive) }
    if (BuildConfig.DEBUG) {
        android.util.Log.d("CurbVoice", curbVoiceLine(readings, curbSignal, curbCue?.phrase, st.crossingActive))
    }
    val hazardCue = if (st.crossingActive) null
    else watchers.hazard.onSignal(HazardSignal.of(readings, st.width, st.height), nowMs, lang)
    val decision = decideObstacles(vehicleCue, curbCue, hazardCue)
    decision.safety?.let {
        safety.speak(it)
        watchers.lastSafetyMs = nowMs
    }
    val soft = decision.soft
    val busy: () -> Boolean = { safetyBusy(System.currentTimeMillis(), watchers.lastSafetyMs) }
    if (soft != null) {
        scope.launch { softSpeech.withLock { voice.onPhrase(soft, playerIdle(), urgent = decision.urgent, queue = true, busy = busy) } }
    } else if (!st.crossingActive) {
        scope.launch { softSpeech.withLock { voice.flushPending(playerIdle(), busy) } }
    }
}
