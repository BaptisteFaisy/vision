package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.debugModelsLine
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairLabelSignals
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import dev.sam3vision.core.settings.resolveSamEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Câblage de la sonde SAM de la page caméra : réglages, voix, fin d'enregistrement.
 *
 * Isolé du ViewModel pour que celui-ci reste sous la limite de 500 lignes : la
 * logique est la même, elle vit simplement à côté.
 */

/**
 * Applique les Réglages à la sonde : endpoint résolu (saisie manuelle > région
 * EU/US > URL du build), guidage « tout droit » (il prime sur l'annonce), annonce
 * vocale des objets et de leur distance, focale de la caméra, distances on/off,
 * plafond d'objets.
 */
internal fun LiveProbe.configureFrom(
    settings: Settings,
    prompts: List<String> = dev.sam3vision.core.live.LivePrompts.default,
    crossingActive: Boolean = false,
) {
    android.util.Log.i("DepthLive", "configure profondeur=" + settings.depthBackend)
    configure(
        baseUrl = settings.resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL),
        prompts = prompts,
        announce = settings.announcePositions,
        guide = settings.guideStraight,
        lens = LensSpec.fromSettings(settings.cameraFovDeg),
        distancesOn = settings.distanceOverlayEnabled,
        maxObjects = settings.objectsOnScreen,
        language = settings.speechLanguage,
        crossingActive = crossingActive,
        modelsLine = debugModelsLine(settings),
        depth = settings.depthBackend,
    )
}

/**
 * Chaque nouvelle phrase de la sonde est confiée à la voix (une seule fois par
 * scène). [playerIdle] vrai = aucune bulle du chat en cours de lecture.
 * Dernier-gagnant (`collectLatest`) : une phrase plus fraîche annule la synthèse
 * en cours au lieu de faire la queue derrière — une consigne datée ne sert à rien.
 * Pré-synthétise aussi le « Stop. » urgent (une fois, en fond).
 */
internal fun LiveProbe.observeSpoken(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
) {
    scope.launch { voice.prewarmStop() }
    scope.launch {
        state.collectLatest { st ->
            val phrase = st.spoken ?: return@collectLatest
            voice.onPhrase(phrase, playerIdle())
        }
    }
}

/**
 * Guetteur trottoir de la sonde : le signal est relu dans l'état (lectures de
 * distance + dimensions, déjà publiées par frame), sans toucher à `LiveProbe`
 * (499/500 lignes — tout câblage vit ici, comme `observeSpoken`). Rend le
 * guetteur pour le `reset()` de début de séance. Coupé en mode Traversée (sa
 * voix rouge/vert est propriétaire du son) ; l'alerte est urgente (coupe).
 */
internal fun LiveProbe.observeCurb(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): CurbWatcher {
    val watcher = CurbWatcher()
    scope.launch {
        state.collectLatest { st ->
            if (st.crossingActive) return@collectLatest
            val readings = st.distanceLayer?.readings ?: return@collectLatest
            val signal = CurbSignal.of(readings, st.width, st.height)
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language()) ?: return@collectLatest
            voice.onPhrase(cue.phrase, playerIdle(), urgent = true)
        }
    }
    return watcher
}

/**
 * Guetteur escaliers de la sonde, miroir du trottoir : les libellés SAM
 * « stairs » lus dans l'état (lectures de distance + dimensions, déjà
 * publiées par frame), sans toucher à `LiveProbe`. Le sens vient des mots du
 * libellé (« stairs going up », exact) sinon de la hauteur de la boîte
 * ([StairLabelSignals]) ; l'annonce est urgente (coupe) mais reste une info
 * de navigation, jamais une consigne d'évitement. Coupé en mode Traversée.
 */
internal fun LiveProbe.observeStairs(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): StairWatcher {
    // Deux frames suffisent côté voix (le flux Canny d'affichage garde trois) :
    // un escalier nommé deux fois de suite n'est plus un clignotement.
    val watcher = StairWatcher(confirmFrames = 2)
    scope.launch {
        state.collectLatest { st ->
            if (st.crossingActive) return@collectLatest
            val readings = st.distanceLayer?.readings ?: return@collectLatest
            val found = StairLabelSignals.of(readings, st.width, st.height)
            val signal = if (found.detected) {
                StairSignal(detected = true, confidence = 1f, lineCount = 0, roiTop = 0, roiBottom = 0)
            } else {
                StairSignal.none()
            }
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language(), found.direction)
                ?: return@collectLatest
            voice.onPhrase(cue.phrase, playerIdle(), urgent = true)
        }
    }
    return watcher
}

/** Les deux guetteurs de la sonde (trottoir + escaliers), reset commun. */
internal class ProbeHazards(
    val curb: CurbWatcher,
    val stairs: StairWatcher,
) {
    /** Oublie les deux guetteurs (nouvelle séance). */
    fun reset() {
        curb.reset()
        stairs.reset()
    }
}

/**
 * Les deux guetteurs en un appel : le ViewModel n'y tient qu'une ligne
 * (plafond 500 — voir `observeCurb`, même contrat).
 */
internal fun LiveProbe.observeHazards(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): ProbeHazards = ProbeHazards(
    observeCurb(scope, voice, playerIdle, language),
    observeStairs(scope, voice, playerIdle, language),
)