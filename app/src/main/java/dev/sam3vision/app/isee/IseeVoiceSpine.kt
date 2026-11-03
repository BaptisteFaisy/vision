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
    /** Horloge du report GPS (temps virtuel sous test). */
    private val clock: () -> Long = { System.currentTimeMillis() },
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

    /** Manœuvre reportée au silence : texte, rang, instant du report (place unique, dernier gagnant). */
    private data class DeferredGps(val text: String, val level: Int, val atMs: Long)

    /** Report en cours (`null` = aucun) : écrasé par le plus frais, sous verrou. */
    private var deferredGps: DeferredGps? = null

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
     * (place unique, dernier gagnant ; périmée au-delà de [GPS_DEFER_TTL_MS],
     * Traversée ou dedans entre-temps : jetée — ordre user du 03/11 : pas
     * d'ancienne donnée ; une manœuvre ne se re-propose pas, la fraîche écrase).
     */
    fun speakGps(text: String, level: Int) {
        if (crossingActive()) return
        if (placeMode() != PictoPlaceMode.DEHORS) return
        if (VoiceBus.stairsSpeaking() || VoiceBus.sidewalkSpeaking()) {
            val crushed = synchronized(this) {
                (deferredGps != null).also { deferredGps = DeferredGps(text, level, clock()) }
            }
            if (crushed) SpeechLog.fail("gps", "report-ecrase")
            scope.launch { deferGpsToGap() }
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

    /**
     * Manœuvre reportée : attendue au silence (scrutation [GPS_GAP_POLL_MS]),
     * puis postée si elle a toujours le créneau (dernier gagnant), si elle
     * est fraîche ([GPS_DEFER_TTL_MS]) et si on est toujours dehors hors
     * Traversée — sinon jetée (journal `gps`).
     */
    private suspend fun deferGpsToGap() {
        val due = synchronized(this) { deferredGps } ?: return
        while (VoiceBus.stairsSpeaking() || VoiceBus.sidewalkSpeaking()) delay(GPS_GAP_POLL_MS)
        val mine = synchronized(this) {
            (deferredGps === due).also { if (it) deferredGps = null }
        }
        if (!mine) return
        if (clock() - due.atMs > GPS_DEFER_TTL_MS) {
            SpeechLog.fail("gps", "report-perime")
            return
        }
        if (crossingActive() || placeMode() != PictoPlaceMode.DEHORS) {
            SpeechLog.fail("gps", "report-jet-contexte")
            return
        }
        if (!VoiceBus.post(due.level, due.text)) SpeechLog.fail("gps", "report-jet-bus")
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

/**
 * Péremption d'une manœuvre reportée : 2 s (un cycle d'escaliers au plus —
 * au-delà, le virage est passé ; ordre user du 03/11 : pas d'ancienne donnée).
 */
private const val GPS_DEFER_TTL_MS = 2_000L
