package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.picto.PictoVoiceText
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Colonne vocale v4 du ViewModel iSee : câblage du bus, bascule Traversée,
 * parole GPS, reprise du chat en attente, trouvé de la chasse. Dépendances
 * injectées (le ViewModel reste sous la garde des 500 lignes).
 */
internal class IseeVoiceSpine(
    private val scope: CoroutineScope,
    private val crossingActive: () -> Boolean,
    private val placeMode: () -> PictoPlaceMode,
    private val cutSpeech: () -> Unit,
    private val stopVoice: () -> Unit,
    private val speakPending: suspend () -> Unit,
    private val speak: suspend (String) -> Unit,
    private val settings: () -> Settings,
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

    /**
     * Trouvé de la chasse : « Mug at 9 o'clock. » — une fois par couple
     * (objet, cadran), l'anti-répétition tait les re-détections. Parle par
     * le chemin navigation ([speak], synthèse vocale) : aucun clip
     * pré-enregistré ne peut couvrir un objet quelconque.
     */
    suspend fun speakFound(label: String, clockHour: Int) {
        val key = AntiRepeat.KEY_FIND_FOUND + ":" + KnownSizes.canonical(label) + ":" + clockHour
        if (!AntiRepeat.allow(key, System.currentTimeMillis())) return
        speak(PictoVoiceText.found(label, clockHour, settings().speechLanguage))
    }
}
