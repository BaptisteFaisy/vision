package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.FindAskPolicy
import dev.sam3vision.core.picto.FindAskStep
import dev.sam3vision.core.picto.FindLabelFromSpeech
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ce que l'écoute sans doigt demande à la dictée : ouvrir le micro, rendre le
 * texte, jeter l'enregistrement. `IseeDictation` l'implémente tel quel —
 * l'écoute du balayage n'a donc aucun chemin micro à elle.
 */
interface FindListening {
    /** Ouvre le micro ; `false` = indisponible (permission, matériel). */
    fun start(): Boolean

    /** Coupe le micro et transcrit, `null` avec le motif déjà affiché. */
    suspend fun finish(settings: Settings): Dictated?

    /** Jette l'enregistrement en cours (silence, refus). */
    fun discard()
}

/**
 * Les deux balayages de la surface iSee (demande user du 02/11/2026) :
 *
 * - **vers le bas** : la fonction GPS, exactement l'ordre vocal « active le
 *   GPS » ([runGpsOrder] → `VoiceNavSession.enableFromVoice` : réglage
 *   `navigationGuidanceEnabled` persisté + accusé parlé) ;
 * - **vers le bas + maintien** (demande user du 03/11/2026) : le doigt resté
 *   posé ouvre le micro ([askDestination]), l'endroit dicté part tel quel en
 *   destination au relâchement ([sendDestination] → [runDestination]) et le
 *   guidage démarre direct, sans question ni bouton ;
 * - **vers la droite** : iSee demande « Quoi chercher ? », ouvre le micro sans
 *   doigt ([listenForObject], fin à la fin de la parole — [FindAskPolicy]),
 *   puis la chasse part par le chemin existant `mode find my X` ([runMode] :
 *   concept SAM, génération du picto à sa première détection, ancrage, refus
 *   dehors et confirmation parlée).
 *
 * Rien n'est réimplémenté ici : les gestes rejouent les ordres vocaux, ils ne
 * parlent ni à SAM ni à la caméra. Pendant une écoute, la surface appartient à
 * la question ([asking]) : l'appui long, le tap et l'autre écoute sont ignorés,
 * sinon deux gestes se disputeraient le même micro.
 *
 * Les deux phrases du geste et les accusés GPS sont des **clips du pack
 * anglais** (`IseeClipsEn.FIND_ASK`, `FIND_NOTHING`, `GPS_ENABLED`…) : zéro
 * synthèse réseau, la voix reste hors-ligne comme le reste de la v4. La
 * question « Quoi chercher ? » est jouée **jusqu'à sa fin** avant que le micro
 * ne s'ouvre ; la destination n'a pas de question (le doigt est déjà posé).
 */
class IseeSwipeOrders(
    private val scope: CoroutineScope,
    private val runMode: suspend (String) -> Boolean,
    private val runGpsOrder: suspend (String) -> Boolean,
    private val dictation: FindListening,
    private val voiceDetected: () -> Boolean,
    private val settings: () -> Settings,
    private val placeMode: () -> PictoPlaceMode,
    /** Joue un clip du pack et attend sa fin (voir `IseeNavSpeaker.speakClipAndWait`). */
    private val sayClip: suspend (String) -> Unit,
    private val update: StateUpdate,
    /** Destination brute entendue → guidage (même chemin que l'ordre « mode GPS … X »). */
    private val runDestination: suspend (String) -> Boolean,
    /** Parler coupe la voix en cours (l'accusé « GPS enabled » du balayage). */
    private val silence: () -> Unit,
    /** Salve franche à l'ouverture du micro destination (même retour que l'appui long). */
    private val hapticStart: () -> Unit,
    /** Queue de 200 ms au relâchement (même retour que l'appui long). */
    private val hapticEnd: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** L'écoute « Quoi chercher ? » tient le micro : les autres gestes se taisent. */
    @Volatile
    private var listening = false

    /** L'écoute destination (maintien après balayage vers le bas) tient le micro. */
    @Volatile
    private var destinationListening = false

    /** Vrai pendant une écoute ; la surface est alors réservée à la question. */
    fun asking(): Boolean = listening || destinationListening

    /**
     * Balayage vers le bas : « active le GPS », exactement l'ordre vocal —
     * réglage `navigationGuidanceEnabled` persisté et accusé parlé, le clip
     * `GPS_ENABLED` portant désormais le texte de l'ordre. Ne lève jamais : la
     * session GPS peut refuser, l'accusé est alors absent, pas l'erreur.
     */
    fun gpsOn() {
        scope.launch { runCatching { runGpsOrder(GPS_ENABLE_ORDER) } }
    }

    /**
     * Balayage vers la droite : « Quoi chercher ? » puis chasse de l'objet
     * entendu. Dehors, refus parlé comme l'ordre vocal (jamais de picto dehors)
     * — et pas de micro ouvert pour rien.
     */
    /**
     * Maintien après balayage vers le bas : le micro destination s'ouvre (même
     * dictée que l'appui long, sans son contexte photo — une destination n'en
     * a pas besoin). Sans effet si une écoute tient déjà le micro.
     */
    fun askDestination() {
        if (listening || destinationListening) return
        destinationListening = true
        silence()
        hapticStart()
        if (dictation.start()) {
            update { it.copy(status = null) }
        } else {
            destinationListening = false
            update { it.copy(status = IseeStatusText.MIC_UNAVAILABLE) }
        }
    }

    /**
     * Relâchement du maintien : l'endroit dicté part tel quel en destination
     * ([runDestination], guidage direct) ; un silence redit « Say again. » et
     * n'envoie rien. Sans effet sans maintien en cours.
     */
    fun sendDestination() {
        if (!destinationListening) return
        destinationListening = false
        hapticEnd()
        scope.launch {
            val heard = dictation.finish(settings()) ?: return@launch
            if (heard.text.isBlank()) {
                nothingHeard()
                return@launch
            }
            runDestination(heard.text)
        }
    }

    /** Maintien annulé (app en arrière-plan en plein maintien) : micro relâché, rien de transcrit. */
    fun cancelDestination() {
        if (!destinationListening) return
        destinationListening = false
        dictation.discard()
        hapticEnd()
    }

    fun askToFind() {
        if (listening || destinationListening) return
        if (!placeMode().pictoEnabled) {
            scope.launch { sayClip(IseeClipsEn.FIND_REFUSED) }
            return
        }
        listening = true
        scope.launch {
            try {
                askThenHunt()
            } finally {
                listening = false
            }
        }
    }

    /** Question parlée (attendue jusqu'à sa fin), écoute, puis chasse du mot entendu. */
    private suspend fun askThenHunt() {
        sayClip(IseeClipsEn.FIND_ASK)
        // Le haut-parleur vient de se taire : un court silence évite de capter
        // sa traîne dans le micro qui s'ouvre.
        delay(ECHO_GUARD_MS)
        val label = listenForObject() ?: return
        runMode("$FIND_PREFIX$label")
    }

    /**
     * Ouvre le micro et rend l'objet entendu, `null` si rien n'est venu (aucun
     * appel réseau, la question est redite) ou si la transcription a échoué
     * (motif déjà affiché).
     */
    private suspend fun listenForObject(): String? {
        if (!dictation.start()) {
            update { it.copy(status = IseeStatusText.MIC_UNAVAILABLE) }
            return null
        }
        if (awaitSpeechEnd() == FindAskStep.DISCARD) {
            dictation.discard()
            return nothingHeard()
        }
        val heard = dictation.finish(settings()) ?: return null
        val label = FindLabelFromSpeech.of(heard.text)
        return label ?: nothingHeard()
    }

    /** Réponse vide : dit et affiché, aucun objet cherché. */
    private suspend fun nothingHeard(): String? {
        update { it.copy(status = IseeStatusText.NOTHING_HEARD) }
        sayClip(IseeClipsEn.FIND_NOTHING)
        return null
    }

    /**
     * Attend la fin de la parole en sondant l'aperçu micro ([FindAskPolicy]) ;
     * `SEND` = il y a de la voix à transcrire, `DISCARD` = silence.
     */
    private suspend fun awaitSpeechEnd(): FindAskStep {
        val startedAt = clock()
        var heard = false
        while (true) {
            delay(FindAskPolicy.POLL_MS)
            val speakingNow = voiceDetected()
            if (speakingNow) heard = true
            val step = FindAskPolicy.step(heard, speakingNow, clock() - startedAt)
            if (step != FindAskStep.LISTEN) return step
        }
    }

    companion object {
        /** Forme dictée de l'ordre d'activation (parsée par `StopGuidanceParser`). */
        const val GPS_ENABLE_ORDER = "active le GPS"

        /** Préfixe de l'ordre de recherche : la forme canonique `mode find my X`. */
        const val FIND_PREFIX = "mode find my "

        /** Silence après la question, avant l'ouverture du micro. */
        const val ECHO_GUARD_MS = 250L
    }
}
