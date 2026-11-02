package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Colonne vocale v4 du ViewModel iSee : câblage du bus, bascule Traversée,
 * parole GPS, reprise du chat en attente, trouvé de la chasse. Dépendances
 * injectées (le ViewModel reste sous la garde des 500 lignes).
 *
 * Le trouvé parle en clips ([speakClips], câblé sur la séquence du lecteur
 * [IseeClipPlayer.speakSequence] en production) ; non câblé, chaque clip part
 * en texte par [speak] et le dernier couvre les précédents (repli audible
 * dégradé — chaque texte se résout en clip, jamais de silence).
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
    private val speakClips: suspend (List<String>) -> Unit =
        { ids -> ids.mapNotNull { IseeClipsEn.all[it] }.forEach { speak(it) } },
) {
    /**
     * Bus vocal (SPEC v4) : le haut-parleur parle, la fin de parole reprend
     * le chat en attente ([resumeChatIfDue]), la tenue du micro tait le bus.
     * Le keepalive de positionnement ([keepaliveJob]) démarre avec lui : en
     * session active dehors, la voix ne se tait jamais (mot courant niveau 12
     * après 3,5 s de silence — le bus arbitre (Traversée affamée incluse :
     * un verdict sain parle toutes les 2 s, le silence 3,5 s n'y arrive pas).
     */
    fun wireBus(speaker: BusSpeaker, ui: StateFlow<IseeUiState>) {
        VoiceBus.speaker = speaker
        VoiceBus.onIdle = { scope.launch { resumeChatIfDue() } }
        scope.launch { ui.collect { VoiceBus.userSpeaking(it.holding || it.transcribing) } }
        keepaliveJob(scope, ui, { settings().speechLanguage }, placeMode)
    }

    /**
     * Dernier état Traversée traité : la coupe et la reprise ne partent que sur
     * une bascule réelle — un ordre répété (voix, puce) ne re-coupe pas.
     */
    private var lastCrossing: Boolean = crossingActive()

    /**
     * Traversée basculée : à l'allumage tout se tait (verdict propriétaire), à
     * l'arrêt le chat reprend. Sans effet si l'état n'a pas changé.
     */
    fun onCrossingChanged() {
        val active = crossingActive()
        if (active == lastCrossing) return
        lastCrossing = active
        if (active) {
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
     * Sous un compte (§ 42) ou un trottoir (§ 45) : reportée au silence
     * (un cycle au plus), jamais perdue (une manœuvre ne se re-propose pas).
     */
    fun speakGps(text: String, level: Int) {
        if (crossingActive()) return
        if (placeMode() != PictoPlaceMode.DEHORS) return
        if (VoiceBus.stairsSpeaking() || VoiceBus.sidewalkSpeaking()) {
            scope.launch { deferGpsToGap(text, level) }
            return
        }
        VoiceBus.post(level, text)
    }

    /** Reprise au repos : la réponse coupée reparle (rang 9, hors Traversée, marches finies). */
    suspend fun resumeChatIfDue() {
        if (crossingActive()) return
        if (VoiceBus.stairsActive(System.currentTimeMillis())) return
        runCatching { speakPending() }
    }

    /** Manœuvre sous un compte ou un trottoir : attendue au silence, puis postée (Traversée entre-temps : jetée). */
    private suspend fun deferGpsToGap(text: String, level: Int) {
        while (VoiceBus.stairsSpeaking() || VoiceBus.sidewalkSpeaking()) delay(GPS_GAP_POLL_MS)
        if (!crossingActive()) VoiceBus.post(level, text)
    }

    /**
     * Trouvé de la chasse, clips uniquement : atome de l'objet
     * ([IseeClipsEn.findTarget] sur le label) sinon [IseeClipsEn.FOUND], puis
     * le cadran ([IseeClipsEn.clock]) — « Keys. » + « 9 o'clock. », joué par
     * [speakClips] (séquence [IseeClipPlayer.speakSequence], pattern verdict).
     * Jamais de texte sans clips : le chemin navigation jette en silence tout
     * texte sans clip (`VoiceMute nav-jet`). Le nom est perdu sauf objet
     * catalogué (« Mug » → « Found. ») : assumé, documenté ici. Une fois par
     * couple (objet, cadran), l'anti-répétition tait les re-détections.
     */
    suspend fun speakFound(label: String, clockHour: Int) {
        val key = AntiRepeat.KEY_FIND_FOUND + ":" + KnownSizes.canonical(label) + ":" + clockHour
        if (!AntiRepeat.allow(key, System.currentTimeMillis())) return
        speakClips(foundClips(label, clockHour))
    }

    /**
     * Clips du trouvé : atome de l'objet ([IseeClipsEn.findTarget], noms et
     * alias normalisés) sinon [IseeClipsEn.FOUND], puis le cadran (omis hors
     * 1..12 : un trouvé sans direction reste audible, jamais menteur).
     */
    private fun foundClips(label: String, clockHour: Int): List<String> {
        val head = IseeClipsEn.findTarget(label) ?: IseeClipsEn.FOUND
        val clock = IseeClipsEn.clock(clockHour)
        return if (clock == null) listOf(head) else listOf(head, clock)
    }
}

/** Scrutation du silence sous un compte (report GPS, § 42). */
private const val GPS_GAP_POLL_MS = 100L
