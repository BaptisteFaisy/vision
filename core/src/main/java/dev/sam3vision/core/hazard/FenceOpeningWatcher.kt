package dev.sam3vision.core.hazard

import dev.sam3vision.core.live.Guidance
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce grillage : [phrase] dans la langue de la voix, [meters] la
 * distance du signal qui l'a déclenché (journal, réglages), [state] et
 * [position] ce qu'elle dit, [level] le niveau VoiceBus v4 (4, grillages) et
 * [antiRepeatKey] sa clé AntiRepeat v4 (`fence`, 4 s).
 */
data class FenceOpeningCue(
    val phrase: String,
    val meters: Float?,
    val state: FenceOpeningState,
    val position: FenceOpeningPosition,
    /** Niveau VoiceBus v4 : grillages, murs et barrières. */
    val level: Int = FenceOpeningWatcher.LEVEL,
    /** Clé AntiRepeat v4 : `fence` (4 s). */
    val antiRepeatKey: String = FenceOpeningWatcher.KEY_FENCE,
)

/**
 * Lissage temporel du [FenceOpeningSignal] (SPEC v4 : grillages à moins de
 * 6 m, niveau 4, silence 4 s), miroir de `HazardWatcher` : un
 * positif isolé ne parle pas, il faut [confirmFrames] positifs consécutifs
 * (l'état annoncé est le dernier), puis silence pendant [cooldownMs].
 *
 * Pur JVM et testé : le branchement (`ProbeWiring.observeFenceOpening`) n'a
 * qu'à appeler [onSignal] puis la synthèse douce existante. Douceur :
 * l'appelant parle avec `urgent = false` (ne coupe rien, attend le silence) —
 * seule l'urgence (stop) coupe. Le cadran ([centerX]) reste opt-in, comme
 * `StairWatcher` : les mots de secteur situent déjà.
 */
class FenceOpeningWatcher(
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
     * quand le compte de confirmation est atteint hors cooldown, `null`
     * sinon. [centerX] ajoute le cadran (`null` = les mots situent, défaut).
     */
    fun onSignal(
        signal: FenceOpeningSignal,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        centerX: Float? = null,
    ): FenceOpeningCue? {
        val state = signal.state
        val position = signal.position
        if (!signal.detected || state == null || position == null) {
            hits = 0
            return null
        }
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        hits = 0
        return FenceOpeningCue(
            FenceOpeningText.phrase(state, position, language, signal.meters, centerX, signal.isGate, signal.middle),
            signal.meters,
            state,
            position,
            level = LEVEL,
            antiRepeatKey = KEY_FENCE,
        )
    }

    /** Oublie le compte et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces (SPEC v4 : 4 s, miroir de la table AntiRepeat). */
        const val DEFAULT_COOLDOWN_MS = 4_000L

        /** Au-delà de cette distance (m), la clôture ne fait pas parler (SPEC v4 : 6 m). */
        const val RANGE_METERS = 6f

        /** Demi-largeur du corps du marcheur (m), miroir du guidage — 0,25 m. */
        private const val BODY_HALF_WIDTH_M = 0.25f

        /**
         * Largeur libre minimale d'un intervalle (m) : le corps
         * (2 × [BODY_HALF_WIDTH_M]) plus la marge d'épaule
         * ([Guidance.SHOULDER_MARGIN_M]) — 0,80 m.
         */
        const val PASS_WIDTH_M = 2f * BODY_HALF_WIDTH_M + Guidance.SHOULDER_MARGIN_M

        /** Niveau VoiceBus v4 : grillages, murs et barrières (< 6 m). */
        const val LEVEL = 4

        /** Clé AntiRepeat v4 : grillages, murs et barrières (4 s). */
        const val KEY_FENCE = "fence"

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
