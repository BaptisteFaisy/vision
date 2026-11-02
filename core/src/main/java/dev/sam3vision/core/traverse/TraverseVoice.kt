package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.curb.SidewalkText
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrases du mode traversée, en anglais. Liste fermée : le pipeline ne fait
 * jamais prononcer autre chose, et ce sont exactement ces phrases que
 * [TraverseVoice.prewarm] copie depuis les MP3 embarqués (voix Laure).
 *
 * Les libellés sont ceux de [CrossingText] (source unique des phrases
 * traversée : un état = une phrase, les deux états de [CrossingState]) plus
 * le but et l'arrivée ([SidewalkText], [CrossingText.arrived]).
 */
object TraversePhrases {
    /** Rouge (urgent) : court, impératif — chaque syllabe compte à un passage piéton. */
    val RED: String = CrossingText.phrase(CrossingState.Red)

    /** Vert (non urgent) : l'autorisation de traverser, jamais préemptive. */
    val GREEN: String = CrossingText.phrase(CrossingState.Green)

    /** But (non urgent) : le trottoir d'en face, une fois le vert parlé. */
    val SIDEWALK_ACROSS: String =
        requireNotNull(SidewalkText.phrase(SidewalkPosition.Across, SpeechLanguage.En))

    /** Arrivée (non urgent) : dite une fois, sur le passage. */
    val ON_CROSSWALK: String = CrossingText.arrived(SpeechLanguage.En)

    /** Toutes les phrases à pré-copier depuis les MP3 embarqués. */
    val ALL: List<String> = listOf(RED, GREEN, SIDEWALK_ACROSS, ON_CROSSWALK)

    /**
     * Clip embarqué de [phrase] (`vp_<id>_en.mp3`), `null` si hors liste.
     * Rouge/vert/but : pack existant (`tools/voicepack-catalog.json`) ;
     * arrivée : `cross_here` (« Here. », PAS `cross_arrived` qui dit
     * « On the crosswalk. »).
     */
    fun clipIdFor(phrase: String): String? = when (phrase) {
        RED -> "cross_red"
        GREEN -> "cross_green"
        SIDEWALK_ACROSS -> "sidewalk_across"
        ON_CROSSWALK -> IseeClipsEn.CROSS_HERE
        else -> null
    }
}

/**
 * Phrase « voiture garée » du mode traversée, FR/EN comme les états moteurs
 * ([VehicleStateText.stoppedPhrase]) : « Voiture garée, à 2 mètres. » /
 * « Parked car, 2 meters away. » — la distance au mètre entier quand elle est
 * mesurée, l'heure du cadran sinon (jamais d'heure devinée sans géométrie).
 * Sans appelant prod (jamais parlée : ordres minimaux seuls) — textes gardés
 * testés pour le guetteur.
 */
object TraverseParkedText {
    /** « Voiture en stationnement… » : même source que le guetteur, nom voiture seul. */
    fun parkedPhrase(meters: Float?, centerX: Float?, language: SpeechLanguage): String =
        VehicleStateText.parkedPhrase(VehicleStateText.VehicleName("Voiture", "Car", "car"), meters, centerX, language)
}

/** Où la phrase a été jouée — ou pourquoi elle ne l'a pas été. */
enum class TraverseVoiceOutcome {
    /** Clip local joué (MP3 embarqué copié au préchauffage). */
    OFFLINE,

    /** Phrase non urgente gardée (lecteur occupé) : dite au silence, la plus fraîche seule. */
    QUEUED,

    /** Doublon : la phrase est déjà en file — on ne la garde pas deux fois. */
    SKIPPED_BUSY,

    /** Rien de jouable : clip absent (ou texte vide). */
    UNAVAILABLE,
}

/** Bilan d'un préchauffage hors-ligne : clips déjà présents, copiés, impossibles. */
data class TraversePrewarm(
    val ready: Int,
    val generated: Int,
    val missing: List<String>,
)

/**
 * Lecture audio du mode traversée. Couture `:app` : `SpeechPlayer`
 * (`stop` + `playFile(file: File): Result<Unit>`, résultats convertis par
 * `isSuccess`).
 *
 * Ici un port pur pour rester testable en JVM.
 */
interface TraversePlayer {
    /** Vrai pendant une lecture en cours (une phrase non urgente est alors tue). */
    val isBusy: Boolean

    /** Coupe la lecture en cours (préemption d'une phrase urgente). */
    fun stop()

    /** Joue un clip déjà sur disque ; `false` si échec. */
    suspend fun playFile(path: String): Boolean
}

/**
 * MP3 embarqués (voix Laure). Couture `:app` : clip résolu par
 * [TraversePhrases.clipIdFor] dans le pack voix, copié vers le chemin du
 * cache.
 */
interface TraverseBundled {
    /** Copie le MP3 embarqué de [phrase] vers [path] ; `false` si aucun clip. */
    fun fill(phrase: String, path: String): Boolean
}

/**
 * Cache local des clips hors-ligne. Couture `:app` : `filesDir/traverse-clips`,
 * un fichier au nom stable par phrase ; utilisable s'il existe et n'est pas vide.
 */
interface TraverseClipStore {
    /** Chemin réservé au clip de [phrase] (écriture au préchauffage). */
    fun pathFor(phrase: String): String

    /** Chemin du clip utilisable, ou `null` si absent ou vide. */
    fun clipFor(phrase: String): String?
}

/**
 * Voix du mode traversée : clips MP3 embarqués uniquement (ordre user :
 * ElevenLabs = LLM + noms GPS) — zéro synthèse réseau ici.
 *
 * Routage ([speak], câblé sur `CrossingCue` par le pipeline —
 * `speak(cue.phrase, cue.urgent)`, le retour vert->rouge arrivant avec
 * `urgent = true`) :
 * - **urgente** — [TraversePlayer.stop] (préemption de la verte en cours) puis
 *   lecture immédiate du clip. Jamais tue pour cause de lecteur occupé : le
 *   `stop` l'a libéré avant. La file est jetée (un ordre nouveau périme le
 *   précédent — jamais de « traversez » après coup).
 * - **non urgente** — gardée ([TraverseVoiceOutcome.QUEUED], doublon
 *   [TraverseVoiceOutcome.SKIPPED_BUSY]) si le lecteur parle déjà (file courte :
 *   une seule, la plus fraîche — miroir `ProximityVoice`), dite au silence avant
 *   la phrase courante, sinon lecture du clip.
 *
 * Hors-ligne ([prewarm], au premier lancement) : chaque phrase est copiée
 * depuis les MP3 embarqués (voix Laure) vers le cache local.
 *
 * Pur et testé en JVM : aucune API Android ici, les trois ports sont moqués.
 *
 * Trace voix ([onEvent], défaut muet) : `EXPECT` à l'entrée de [speak], puis
 * l'issue (`OFFLINE`, `QUEUED`, `SKIPPED_BUSY`, `UNAVAILABLE`) à chaque
 * retour. Une phrase gardée, drainée au silence avant la courante, émet
 * `DRAIN` puis sa propre issue, dans l'ordre parlé.
 */
class TraverseVoice(
    private val player: TraversePlayer,
    private val store: TraverseClipStore,
    private val bundled: TraverseBundled,
    private val onEvent: (String) -> Unit = {},
) {
    /**
     * Phrase douce gardée (file courte : une seule, la plus fraîche), `null` si aucune.
     */
    private var pending: String? = null

    /**
     * Copie les phrases depuis les MP3 embarqués. À appeler au premier
     * lancement ; les appels suivants ne coûtent qu'une vérification de présence.
     * Vide aussi la file (nouvelle session : un ordre gardé ne survit pas à l'activation).
     */
    suspend fun prewarm(phrases: List<String> = TraversePhrases.ALL): TraversePrewarm {
        pending = null
        var ready = 0
        var generated = 0
        val missing = mutableListOf<String>()
        phrases.forEach { phrase ->
            if (store.clipFor(phrase) != null) {
                ready++
            } else if (bundled.fill(phrase, store.pathFor(phrase))) {
                generated++
            } else {
                missing += phrase
            }
        }
        return TraversePrewarm(ready, generated, missing)
    }

    /**
     * Prononce [phrase] : [urgent] `= true` préempte et joue toujours (la rouge,
     * la file est jetée — un ordre nouveau périme le précédent),
     * `false` se garde si le lecteur est occupé ([TraverseVoiceOutcome.QUEUED],
     * doublon [TraverseVoiceOutcome.SKIPPED_BUSY]) et se dit au silence avant la
     * phrase courante. Clip local dans tous les cas.
     */
    suspend fun speak(phrase: String, urgent: Boolean): TraverseVoiceOutcome {
        onEvent("EXPECT")
        if (phrase.isBlank()) return outcome(TraverseVoiceOutcome.UNAVAILABLE)
        if (urgent) {
            pending = null
            player.stop()
        } else if (player.isBusy) {
            if (phrase == pending) return outcome(TraverseVoiceOutcome.SKIPPED_BUSY)
            pending = phrase
            return outcome(TraverseVoiceOutcome.QUEUED)
        } else {
            drainPending(phrase)
        }
        return outcome(playChain(phrase))
    }

    /** Retour tracé : l'issue part au parent, qui la corrèle à l'`EXPECT` d'entrée. */
    private fun outcome(out: TraverseVoiceOutcome): TraverseVoiceOutcome {
        onEvent(out.name)
        return out
    }

    /** Chaîne audio : clip local, rien d'autre (zéro synthèse réseau). */
    private suspend fun playChain(phrase: String): TraverseVoiceOutcome =
        if (playOffline(phrase)) TraverseVoiceOutcome.OFFLINE
        else TraverseVoiceOutcome.UNAVAILABLE

    /**
     * Dit la phrase gardée avant [phrase] (au silence : l'appelant est libre) —
     * sauf doublon (la lecture courante la dit déjà). La phrase courante suit
     * dans tous les cas ; la gardée trace `DRAIN` puis sa propre issue.
     */
    private suspend fun drainPending(phrase: String) {
        val due = pending
        pending = null
        if (due != null && due != phrase) {
            onEvent("DRAIN")
            onEvent(playChain(due).name)
        }
    }

    /** Repli hors-ligne : clip pré-copié rejoué tel quel. */
    private suspend fun playOffline(phrase: String): Boolean {
        val clip = store.clipFor(phrase) ?: return false
        return player.playFile(clip)
    }
}
