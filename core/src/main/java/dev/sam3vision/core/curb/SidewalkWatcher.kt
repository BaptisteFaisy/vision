package dev.sam3vision.core.curb

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce trottoir : [phrase] dans la langue de la voix, [position]
 * celle qui l'a déclenché (journal, réglages).
 */
data class SidewalkCue(
    val phrase: String,
    val position: SidewalkPosition,
)

/**
 * Phrases trottoir, FR/EN comme les positions : une info de navigation
 * (« Trottoir à gauche. »), jamais une consigne — l'ordre de retour, lui,
 * passe par les mots du guidage (« Gauche. »).
 */
object SidewalkText {
    /**
     * « Trottoir à gauche. » — `null` pour [SidewalkPosition.Absent] : le
     * doute ne se dit jamais (le guetteur le filtre déjà, ceinture et
     * bretelles).
     */
    fun phrase(position: SidewalkPosition, language: SpeechLanguage): String? {
        if (position == SidewalkPosition.Absent) return null
        return when (language) {
            SpeechLanguage.Fr -> when (position) {
                SidewalkPosition.Ahead -> "Trottoir devant."
                SidewalkPosition.Left -> "Trottoir à gauche."
                SidewalkPosition.Right -> "Trottoir à droite."
                SidewalkPosition.Across -> "Trottoir en face."
                SidewalkPosition.Absent -> null
                else -> "Trottoir à droite."
            }
            SpeechLanguage.En -> when (position) {
                SidewalkPosition.Ahead -> "Sidewalk ahead."
                SidewalkPosition.Left -> "Sidewalk on the left."
                SidewalkPosition.Right -> "Sidewalk on the right."
                SidewalkPosition.Across -> "Sidewalk across the street."
                SidewalkPosition.Absent -> null
                else -> "Sidewalk on the right."
            }
        }
    }
}

/**
 * Lissage temporel de la position trottoir : il faut [confirmFrames]
 * positifs consécutifs pour parler, et on ne parle qu'au changement —
 * stable = silence (le guidage confirme déjà qu'il veille). L'absent (ou le
 * `null`, guidage coupé) remet le compte à zéro sans parler et sans effacer
 * la dernière annonce : un trou de frame ne fait pas répéter.
 *
 * Pur JVM et testé : le branchement (`ProbeWiring.observeSidewalk`) n'a qu'à
 * appeler [onPosition] puis la synthèse douce existante.
 */
class SidewalkWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
) {
    /** Positifs consécutifs de la position en cours (autre chose remet à zéro). */
    private var hits: Int = 0

    /** Position en cours de confirmation, `null` si aucune. */
    private var pending: SidewalkPosition? = null

    /** Dernière position annoncée, `null` si aucune. */
    private var lastSpoken: SidewalkPosition? = null

    /** Instant de la dernière annonce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /**
     * Alimente le lissage avec la [position] de la frame à [nowMs] : l'annonce
     * quand la confirmation aboutit sur une position nouvelle, `null` sinon.
     * [language] est la langue de la voix (Réglages).
     */
    fun onPosition(
        position: SidewalkPosition?,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): SidewalkCue? {
        if (position == null || position == SidewalkPosition.Absent) {
            hits = 0
            pending = null
            return null
        }
        if (position != pending) {
            pending = position
            hits = 0
        }
        hits++
        if (hits < confirmFrames) return null
        hits = 0
        pending = null
        if (position == lastSpoken) return null
        val phrase = SidewalkText.phrase(position, language) ?: return null
        lastSpoken = position
        lastCueAtMs = nowMs
        return SidewalkCue(phrase, position)
    }

    /** Oublie le compte et l'annonce (nouvelle séance). */
    fun reset() {
        hits = 0
        pending = null
        lastSpoken = null
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
