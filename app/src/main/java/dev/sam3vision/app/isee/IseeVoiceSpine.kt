package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.FindIndoorClips
import dev.sam3vision.core.picto.FindIndoorLevels
import dev.sam3vision.core.picto.FindIndoorTarget
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.live.AntiRepeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Colonne vocale v4 du ViewModel iSee : câblage du bus, bascule Traversée,
 * parole GPS, reprise du chat en attente. Dépendances injectées (le
 * ViewModel reste sous la garde des 500 lignes).
 */
internal class IseeVoiceSpine(
    private val scope: CoroutineScope,
    private val crossingActive: () -> Boolean,
    private val placeMode: () -> PictoPlaceMode,
    private val cutSpeech: () -> Unit,
    private val stopVoice: () -> Unit,
    private val speakPending: suspend () -> Unit,
) {
    /**
     * Bus vocal (SPEC v4) : le haut-parleur parle, la fin de parole reprend
     * le chat en attente ([resumeChatIfDue]), la tenue du micro tait le bus.
     */
    fun wireBus(speaker: BusSpeaker, ui: StateFlow<IseeUiState>) {
        VoiceBus.speaker = speaker
        VoiceBus.onIdle = { scope.launch { resumeChatIfDue() } }
        scope.launch { ui.collect { VoiceBus.userSpeaking(it.holding || it.transcribing) } }

    }

    /** Traversée basculée : à l'allumage tout se tait (verdict propriétaire), à l'arrêt le chat reprend. */
    fun onCrossingChanged() {
        if (crossingActive()) {
            cutSpeech()
            stopVoice()
        } else {
            scope.launch { resumeChatIfDue() }
        }
    }

    /**
     * Parole GPS vers le bus (SPEC v4) : manœuvres au rang 11, rappel au
     * rang 3 — tus en Traversée (le verdict y est propriétaire du son) et
     * dedans (chaîne dedans : find, user, humain, chat, porte et murs).
     */
    fun speakGps(text: String, level: Int) {
        if (crossingActive()) return
        if (placeMode() != PictoPlaceMode.DEHORS) return
        VoiceBus.post(level, text)
    }

    /** Reprise au repos : la réponse coupée reparle (rang 9, hors Traversée). */
    suspend fun resumeChatIfDue() {
        if (crossingActive()) return
        runCatching { speakPending() }
    }
}

/**
 * Trouvé de la chasse (SPEC v4, cran find 0) : le clip « Found … » part, une fois par
 * couple (objet, cadran) — l'anti-répétition tait les re-détections.
 */
internal fun postFoundClip(label: String, clockHour: Int) {
    val target = FindIndoorTarget.fromSpoken(label) ?: return
    val clip = FindIndoorClips.found(target, clockHour)
    if (AntiRepeat.allow(AntiRepeat.KEY_FIND_FOUND + ":" + clip.id, System.currentTimeMillis())) {
        VoiceBus.post(FindIndoorLevels.FIND, clip.text, clip.id)
    }
}
