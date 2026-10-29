package dev.sam3vision.core.stairs

import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce d'escalier : [phrase] dans la langue de la voix, [direction]
 * son sens, [confidence] et [lineCount] du signal qui l'a déclenché (journal,
 * réglages ; voie labels SAM : confiance 1, 0 ligne — le Canny seul compte).
 */
data class StairCue(
    val phrase: String,
    val confidence: Float,
    val lineCount: Int,
    val direction: StairDirection = StairDirection.Unknown,
)

/**
 * Phrases d'escalier, FR/EN comme les positions (`PositionText`) : une info de
 * navigation (« Escalier montant. »), jamais une consigne d'évitement — on ne
 * contourne pas l'escalier qu'on veut prendre.
 */
object StairText {
    /** « Escalier montant, à 11 heures. » (`null` = sans heure, ex. flux Canny). */
    fun phrase(
        language: SpeechLanguage,
        direction: StairDirection = StairDirection.Unknown,
        centerX: Float? = null,
    ): String {
        val base = when (language) {
            SpeechLanguage.Fr -> when (direction) {
                StairDirection.Up -> "Escalier montant"
                StairDirection.Down -> "Escalier descendant"
                StairDirection.Unknown -> "Escalier devant"
            }
            SpeechLanguage.En -> when (direction) {
                StairDirection.Up -> "Stairs up"
                StairDirection.Down -> "Stairs down"
                StairDirection.Unknown -> "Stairs ahead"
            }
        }
        return PositionText.withHour(base, centerX, language)
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

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint hors cooldown, `null` sinon.
     * [direction] est le sens annoncé (voie labels SAM ; le Canny seul ne le
     * voit pas et garde [StairDirection.Unknown]). [centerX] est le centre
     * normalisé (l'heure du cadran, `null` = sans heure).
     */
    fun onSignal(
        signal: StairSignal,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
        direction: StairDirection = StairDirection.Unknown,
        centerX: Float? = null,
    ): StairCue? {
        if (!signal.detected) {
            hits = 0
            return null
        }
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        hits = 0
        return StairCue(
            StairText.phrase(language, direction, centerX), signal.confidence, signal.lineCount, direction,
        )
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
