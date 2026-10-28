package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.net.TtsClient

/**
 * Phrases du mode traversée, en français. Liste fermée : le pipeline ne fait
 * jamais prononcer autre chose, et ce sont exactement ces phrases que
 * [TraverseVoice.prewarm] pré-génère en cache hors-ligne au premier lancement.
 *
 * Les libellés sont ceux de [CrossingText] (source unique des phrases
 * traversée) : un état = une phrase, les trois états de [CrossingState].
 */
object TraversePhrases {
    /** Rouge (urgent) : court, impératif — chaque syllabe compte à un passage piéton. */
    val RED: String = CrossingText.phrase(CrossingState.Red)

    /** Vert (non urgent) : l'autorisation de traverser, jamais préemptive. */
    val GREEN: String = CrossingText.phrase(CrossingState.Green)

    /** Doute (non urgent) : dit pourquoi on ne traverse pas, comme le rouge. */
    val UNKNOWN: String = CrossingText.phrase(CrossingState.Unknown)

    /** Toutes les phrases à pré-générer hors-ligne. */
    val ALL: List<String> = listOf(RED, GREEN, UNKNOWN)

    /** Préfixe BCP-47 attendu du moteur système (« fr », « fr-FR », …). */
    const val LANGUAGE_PREFIX = "fr"
}

/** Où la phrase a été jouée — ou pourquoi elle ne l'a pas été. */
enum class TraverseVoiceOutcome {
    /** Synthèse ElevenLabs jouée (lecteur préempté si la phrase était urgente). */
    PRIMARY,

    /** Clip hors-ligne du cache local joué (ElevenLabs en échec ou absent). */
    OFFLINE,

    /** Phrase non urgente tue : le lecteur parlait déjà (une verte ne couvre jamais une rouge). */
    SKIPPED_BUSY,

    /** Rien de jouable : ni réseau, ni clip en cache (ou texte vide). */
    UNAVAILABLE,
}

/** Bilan d'un préchauffage hors-ligne : clips déjà présents, générés, impossibles. */
data class TraversePrewarm(
    val ready: Int,
    val generated: Int,
    val missing: List<String>,
    /** Vrai si le moteur système parle effectivement français. */
    val languageOk: Boolean
)

/**
 * Lecture audio du mode traversée. Couture `:app` : `SpeechPlayer`
 * (`stop` + `play(bytes): Result<Unit>` + `playFile(file: File): Result<Unit>`,
 * résultats convertis par `isSuccess`), avec deux points d'attention :
 * - `isBusy` se dérive de `position()` (non `null` pendant une lecture) ;
 * - `play(bytes)` vide son répertoire avant d'écrire : l'adaptateur lui donne
 *   un répertoire dédié, séparé du [TraverseClipStore] (sinon les clips meurent
 *   à la première synthèse).
 *
 * Ici un port pur pour rester testable en JVM.
 */
interface TraversePlayer {
    /** Vrai pendant une lecture en cours (une phrase non urgente est alors tue). */
    val isBusy: Boolean

    /** Coupe la lecture en cours (préemption d'une phrase urgente). */
    fun stop()

    /** Joue des octets audio (ElevenLabs) ; `false` si la lecture a échoué. */
    suspend fun play(bytes: ByteArray): Boolean

    /** Joue un clip déjà sur disque (cache hors-ligne) ; `false` si échec. */
    suspend fun playFile(path: String): Boolean
}

/**
 * TTS système Android hors-ligne. Couture `:app` : un adaptateur `suspend`
 * autour de `android.speech.tts.TextToSpeech` (`setLanguage` +
 * `synthesizeToFile` + `UtteranceProgressListener`, voix embarquée, aucun
 * réseau) — `SystemTtsEngine` parle mais ne rend pas en fichier.
 */
interface TraverseSystemTts {
    /** Rend [phrase] dans le fichier [path] ; le chemin écrit, ou l'échec. */
    suspend fun renderToFile(phrase: String, path: String): Result<String>

    /** Langue effective du moteur (« fr-FR », « en-US », …) — vérifiée FR au préchauffage. */
    fun effectiveLanguage(): String
}

/**
 * Cache local des clips hors-ligne. Couture `:app` : `filesDir/traverse-voice`,
 * un fichier au nom stable par phrase ; utilisable s'il existe et n'est pas vide.
 */
interface TraverseClipStore {
    /** Chemin réservé au clip de [phrase] (écriture au préchauffage). */
    fun pathFor(phrase: String): String

    /** Chemin du clip utilisable, ou `null` si absent ou vide. */
    fun clipFor(phrase: String): String?
}

/**
 * Voix du mode traversée : la phrase rouge passe toujours, même sans réseau.
 *
 * Routage ([speak], câblé sur `CrossingCue` par le pipeline —
 * `speak(cue.phrase, cue.urgent)`, le retour vert->rouge arrivant avec
 * `urgent = true`) :
 * - **urgente** — [TraversePlayer.stop] (préemption de la verte en cours) puis
 *   lecture immédiate : ElevenLabs d'abord, clip hors-ligne en repli. Jamais tue
 *   pour cause de lecteur occupé : le `stop` l'a libéré avant.
 * - **non urgente** — tue ([TraverseVoiceOutcome.SKIPPED_BUSY]) si le lecteur
 *   parle déjà, sinon même chaîne ElevenLabs puis cache.
 *
 * Hors-ligne ([prewarm], au premier lancement) : chaque phrase FR est rendue une
 * fois par le TTS système dans le cache local, et la langue effective du moteur
 * est vérifiée FR ([TraversePrewarm.languageOk]). Le repli est ensuite automatique
 * : ElevenLabs en échec ou sans clé rejoue le clip local, sans intervention.
 *
 * Pur et testé en JVM : aucune API Android ici, les quatre ports sont moqués.
 */
class TraverseVoice(
    private val primary: TtsClient,
    private val player: TraversePlayer,
    private val systemTts: TraverseSystemTts,
    private val store: TraverseClipStore,
) {
    /**
     * Pré-génère les phrases FR via le TTS système. À appeler au premier
     * lancement ; les appels suivants ne coûtent qu'une vérification de présence.
     */
    suspend fun prewarm(phrases: List<String> = TraversePhrases.ALL): TraversePrewarm {
        var ready = 0
        var generated = 0
        val missing = mutableListOf<String>()
        phrases.forEach { phrase ->
            if (store.clipFor(phrase) != null) {
                ready++
            } else if (systemTts.renderToFile(phrase, store.pathFor(phrase)).isSuccess) {
                generated++
            } else {
                missing += phrase
            }
        }
        return TraversePrewarm(ready, generated, missing, languageOk = isFrench())
    }

    /**
     * Prononce [phrase] : [urgent] `= true` préempte et joue toujours (la rouge),
     * `false` se tait si le lecteur est occupé (la verte). Chaîne audio dans les
     * deux cas : ElevenLabs, puis clip hors-ligne en repli automatique.
     */
    suspend fun speak(phrase: String, urgent: Boolean): TraverseVoiceOutcome {
        if (phrase.isBlank()) return TraverseVoiceOutcome.UNAVAILABLE
        if (urgent) {
            player.stop()
        } else if (player.isBusy) {
            return TraverseVoiceOutcome.SKIPPED_BUSY
        }
        if (playPrimary(phrase)) return TraverseVoiceOutcome.PRIMARY
        if (playOffline(phrase)) return TraverseVoiceOutcome.OFFLINE
        return TraverseVoiceOutcome.UNAVAILABLE
    }

    /** Synthèse ElevenLabs puis lecture ; `false` sur erreur réseau, clé absente ou lecture ratée. */
    private suspend fun playPrimary(phrase: String): Boolean {
        val bytes = primary.synthesize(phrase).getOrNull() ?: return false
        if (bytes.isEmpty()) return false
        return player.play(bytes)
    }

    /** Repli hors-ligne : clip pré-généré rejoué tel quel. */
    private suspend fun playOffline(phrase: String): Boolean {
        val clip = store.clipFor(phrase) ?: return false
        return player.playFile(clip)
    }

    /** Le moteur système parle-t-il français ? (« fr », « fr-FR », « fra », …). */
    private fun isFrench(): Boolean =
        systemTts.effectiveLanguage().trim().lowercase().startsWith(TraversePhrases.LANGUAGE_PREFIX)
}
