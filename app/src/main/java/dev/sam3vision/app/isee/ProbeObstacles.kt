package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Les deux guetteurs obstacles (bordure + mobilier) : les véhicules ne
 * parlent qu'en Traversée (verdict 100), jamais dehors (ordre user du
 * 02/11).
 */
internal class ObstacleWatchers(
    val curb: CurbWatcher,
    val hazard: HazardWatcher,
) {
    /** Oublie les deux guetteurs (nouvelle séance). */
    fun reset() {
        curb.reset()
        hazard.reset()
    }
}

/**
 * Guetteur obstacles de la sonde, SPEC v4 : bordure et mobilier calculés
 * sur la même frame, **un seul gagnant** ([decideFrame] : le plus petit
 * niveau — 2 humain, 4 barrière, 5 bordure, 7 divers ; 101 humain et 102
 * bordure en Traversée) proposé au bus ([offer] : antirépétition puis
 * [VoiceBus.post]). L'écrasé n'est jamais redit ; le bus écrase entre
 * producteurs (escaliers, grillage, trottoir, GPS…).
 */
internal fun LiveProbe.observeObstacles(
    scope: CoroutineScope,
    language: () -> SpeechLanguage,
    curb: CurbWatcher = CurbWatcher(),
    hazard: HazardWatcher = HazardWatcher(),
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
): ObstacleWatchers {
    val watchers = ObstacleWatchers(curb, hazard)
    scope.launch {
        state.collect { st ->
            obstacleFrame(st, language, watchers, placeMode)
        }
    }
    return watchers
}

/**
 * Une frame d'obstacles : les deux signaux relus dans l'état (lectures de
 * distance + dimensions, déjà publiées par frame), convertis en vœux
 * ([hazardWish], [curbWish] — mot d'évitement continu en clip immédiat,
 * humain niveau 2, poteau niveau 7), arbitrés ([decideFrame]) puis proposés
 * au bus ([offer]). `collect` séquentiel : une alerte confirmée ne
 * s'annule pas.
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
    val curbSignal = CurbSignal.of(readings, st.width, st.height)
    val curbCue = if (st.crossingActive && curbSignal.tier != CurbTier.NEAR) null
    else watchers.curb.onSignal(curbSignal, nowMs, lang)?.takeIf { shouldSpeakCurb(it, st.crossingActive) }
    if (BuildConfig.DEBUG) android.util.Log.d("CurbVoice", curbVoiceLine(readings, curbSignal, curbCue?.phrase, st.crossingActive))
    val hazardSignal = HazardSignal.of(readings, st.width, st.height)
    val hazardCue = watchers.hazard.onSignal(hazardSignal, nowMs, lang)
    if (BuildConfig.DEBUG) android.util.Log.d("HazardVoice", hazardVoiceLine(readings, hazardSignal, hazardCue?.phrase))
    // Dedans : seule la personne parle (cran 2, le même que dehors) —
    // bordures, mobilier et 101/102 se taisent (le verdict 100, lui,
    // parle toujours en Traversée).
    val indoor = placeMode() == PictoPlaceMode.DEDANS
    // Mot de guidage en cours : la frame attend sans consommer son tour
    // d'antirépétition (ordre user du 02/11/2026 — le bus jetterait les
    // vœux de toute façon, tours mangés ; ≤0,5 s).
    val guidanceHolds = !indoor && VoiceBus.guidanceSpeaking()
    offer(
        decideFrame(
            listOf(
                hazardWish(hazardCue, st.crossingActive)
                    ?.takeIf { (!indoor || (!st.crossingActive && hazardCue?.kind == HazardKind.PERSON)) && !guidanceHolds },
                curbWish(curbCue, st.crossingActive).takeUnless { indoor || guidanceHolds },
            ),
        ),
        nowMs,
    )
}
