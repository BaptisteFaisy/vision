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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * Dernier-gagnant : une phrase plus fraîche annule la synthèse en cours au lieu
 * de faire la queue derrière — une consigne datée ne sert à rien. La même phrase
 * ré-émise par les frames suivantes n'annule rien (`distinctUntilChanged`) :
 * annuler à chaque frame, c'était ne jamais finir une synthèse lente.
 * L'urgence vient de l'état ([LiveProbeState.spokenUrgent], couloir bouché) :
 * un contournement coupe la lecture en cours, chat compris. Le flag ne change
 * qu'avec la phrase, donc la paire reste aussi stable que la phrase seule.
 */
internal fun LiveProbe.observeSpoken(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
) {
    scope.launch {
        state.map { it.spoken to it.spokenUrgent }.distinctUntilChanged().collectLatest { (phrase, urgent) ->
            val text = phrase ?: return@collectLatest
            voice.onPhrase(text, playerIdle(), urgent = urgent)
        }
    }
}

/**
 * Guetteur trottoir de la sonde : le signal est relu dans l'état (lectures de
 * distance + dimensions, déjà publiées par frame), sans toucher à `LiveProbe`
 * (499/500 lignes — tout câblage vit ici, comme `observeSpoken`). Rend le
 * guetteur pour le `reset()` de début de séance. Coupé en mode Traversée (sa
 * voix rouge/vert est propriétaire du son) ; l'alerte est urgente (coupe).
 * Lecture séquentielle (`collect`) : une alerte confirmée est rare, et elle ne
 * doit pas être annulée par la frame suivante.
 */
internal fun LiveProbe.observeCurb(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): CurbWatcher {
    val watcher = CurbWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val signal = CurbSignal.of(readings, st.width, st.height)
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language()) ?: return@collect
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
 * Lecture séquentielle, comme le trottoir : une alerte confirmée ne s'annule pas.
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
        state.collect { st ->
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val found = StairLabelSignals.of(readings, st.width, st.height)
            val signal = if (found.detected) {
                StairSignal(detected = true, confidence = 1f, lineCount = 0, roiTop = 0, roiBottom = 0)
            } else {
                StairSignal.none()
            }
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language(), found.direction)
                ?: return@collect
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

    /**
     * TwinLite doit-il se taire à [nowMs] ? Oui dans les [MUTE_MS] qui suivent
     * une alerte : le danger nommé a la priorité sur le guidage du sol (la
     * flèche reste affichée, seule la voix se tait). La fenêtre couvre les
     * cooldowns des guetteurs : condition persistante = alertes chaînées =
     * silence TwinLite continu ; condition partie = reprise après la fenêtre.
     */
    fun mutesTwinLite(nowMs: Long): Boolean =
        recent(curb.lastAlertMs, nowMs) || recent(stairs.lastAlertMs, nowMs)

    companion object {
        /** Fenêtre de priorité après une alerte (couvre les cooldowns guetteurs). */
        val MUTE_MS: Long = maxOf(CurbWatcher.DEFAULT_COOLDOWN_MS, StairWatcher.DEFAULT_COOLDOWN_MS)

        private fun recent(alertMs: Long?, nowMs: Long): Boolean =
            alertMs != null && nowMs - alertMs in 0..<MUTE_MS
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