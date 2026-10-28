package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce « escalier devant » : [phrase] dans la langue de la voix,
 * [confidence] et [lineCount] du signal qui l'a déclenché (journal, réglages).
 */
data class StairCue(
    val phrase: String,
    val confidence: Float,
    val lineCount: Int,
)

/**
 * Phrases « escalier devant », FR/EN comme les positions (`PositionText`).
 * Liste fermée d'une seule phrase : le préchauffage hors-ligne n'a qu'un clip
 * à générer quand le câblage voix sera branché (voir `StairDetector`).
 */
object StairText {
    /** « Escalier devant. » / « Stairs ahead. » selon [language]. */
    fun phrase(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Escalier devant."
        SpeechLanguage.En -> "Stairs ahead."
    }
}

/**
 * Lissage temporel du [StairSignal] : un positif isolé ne parle pas, il faut
 * [confirmFrames] positifs consécutifs, puis silence pendant [cooldownMs]
 * (miroir du `VOICE_COOLDOWN` MIT, 3 s, monté à 10 s : un escalier ne bouge
 * pas, inutile de le répéter à chaque pas).
 *
 * Pur JVM et testé : le branchement au direct (`LiveViewModel.onFrame`)
 * n'aura qu'à appeler [onSignal] puis la synthèse existante.
 */
class StairWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    /** Positifs consécutifs en cours (un négatif remet à zéro). */
    private var hits: Int = 0

    /** Instant de la dernière annonce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint hors cooldown, `null` sinon.
     */
    fun onSignal(signal: StairSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.Fr): StairCue? {
        if (!signal.detected) {
            hits = 0
            return null
        }
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        hits = 0
        return StairCue(StairText.phrase(language), signal.confidence, signal.lineCount)
    }

    /** Oublie le compte et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 3

        /** Silence entre deux annonces (l'escalier ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
