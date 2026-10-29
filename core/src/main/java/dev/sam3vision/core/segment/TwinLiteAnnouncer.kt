package dev.sam3vision.core.segment

import dev.sam3vision.core.live.AnnouncementGate
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal vocal du guidage TwinLiteNet : [phrase] à prononcer, [urgent] si elle
 * doit couper la phrase en cours ([TwinLiteDirection.STOP] : l'obstacle est
 * déjà là), [direction] la direction annoncée (pastille UI).
 */
data class TwinLiteCue(
    val phrase: String,
    val urgent: Boolean,
    val direction: TwinLiteDirection,
)

/**
 * Déclencheur du guidage TwinLiteNet, pendant de
 * [dev.sam3vision.core.live.GuidanceAnnouncer] sur la zone praticable : la
 * machine anti-répétition (stabilité, nouveauté, délai) est [AnnouncementGate],
 * et **l'arrêt parle tout de suite** — un obstacle qui bouche les trois tiers
 * ne doit pas attendre la fin de la phrase en cours (le moteur TTS coupe : une
 * seule lecture à la fois).
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [atMs] est fourni).
 */
class TwinLiteAnnouncer(
    minFrames: Int = AnnouncementGate.DEFAULT_MIN_FRAMES,
    cooldownMs: Long = AnnouncementGate.DEFAULT_COOLDOWN_MS,
) {

    private val gate = AnnouncementGate(minFrames, cooldownMs)

    /** Dernier signal produit (`null` tant que rien n'a été annoncé). */
    var lastCue: TwinLiteCue? = null
        private set

    /**
     * Conseil observé à [atMs] : le signal à prononcer, ou `null` pour garder
     * le silence. [language] est la langue de la voix (Réglages) : elle ne
     * change ni les seuils, ni la mémoire de décision.
     *
     * [advice] `null` (segmentation tombée) casse la série : la stabilité
     * retombe pour que le retour de la scène parle, et rien n'est dit.
     */
    fun onAdvice(
        advice: TwinLiteAdvice?,
        atMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): TwinLiteCue? {
        if (advice == null) {
            gate.clearScene()
            return null
        }
        val urgent = advice.direction == TwinLiteDirection.STOP
        val key = TwinLiteText.key(advice.direction)
        if (!gate.admit(atMs, key, urgent)) return null
        val phrase = TwinLiteText.phrase(advice.direction, language)
        gate.markSpoken(key, atMs, phrase)
        val cue = TwinLiteCue(phrase, urgent, advice.direction)
        lastCue = cue
        return cue
    }

    /** Nouvelle session : tout est oublié (le premier conseil stable sera annoncé). */
    fun reset() {
        gate.reset()
        lastCue = null
    }
}
