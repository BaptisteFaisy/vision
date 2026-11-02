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
 * - **vers la droite** : iSee demande « Quoi chercher ? », ouvre le micro sans
 *   doigt ([listenForObject], fin à la fin de la parole — [FindAskPolicy]),
 *   puis la chasse part par le chemin existant `mode find my X` ([runMode] :
 *   concept SAM, génération du picto à sa première détection, ancrage, refus
 *   dehors et confirmation parlée).
 *
 * Rien n'est réimplémenté ici : les deux gestes rejouent les ordres vocaux, ils
 * ne parlent ni à SAM ni à la caméra. Pendant l'écoute, la surface appartient à
 * la question ([asking]) : l'appui long et le tap sont ignorés, sinon deux
 * gestes se disputeraient le même micro.
 *
 * Les deux phrases du geste et les accusés GPS sont des **clips du pack
 * anglais** (`IseeClipsEn.FIND_ASK`, `FIND_NOTHING`, `GPS_ENABLED`…) : zéro
 * synthèse réseau, la voix reste hors-ligne comme le reste de la v4. La
 * question est jouée **jusqu'à sa fin** avant que le micro ne s'ouvre.
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
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** L'écoute « Quoi chercher ? » tient le micro : les autres gestes se taisent. */
    @Volatile
    private var listening = false

    /** Vrai pendant l'écoute ; la surface est alors réservée à la question. */
    fun asking(): Boolean = listening

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
    fun askToFind() {
        if (listening) return
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
