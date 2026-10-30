package dev.sam3vision.core.hazard

import dev.sam3vision.core.live.Guidance
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce grillage : [phrase] dans la langue de la voix, [meters] la
 * distance du signal qui l'a déclenché (journal, réglages), [state] et
 * [position] ce qu'elle dit.
 */
data class FenceOpeningCue(
    val phrase: String,
    val meters: Float?,
    val state: FenceOpeningState,
    val position: FenceOpeningPosition,
)

/**
 * Lissage temporel du [FenceOpeningSignal], miroir de `HazardWatcher` : un
 * positif isolé ne parle pas, il faut [confirmFrames] positifs consécutifs
 * (l'état annoncé est le dernier), puis silence pendant [cooldownMs] (une
 * clôture ne bouge pas).
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
        language: SpeechLanguage = SpeechLanguage.Fr,
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

        /** Silence entre deux annonces (la clôture ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), la clôture ne fait pas parler (portée du guidage). */
        const val RANGE_METERS = 4f

        /** Demi-largeur du corps du marcheur (m), miroir du guidage — 0,25 m. */
        private const val BODY_HALF_WIDTH_M = 0.25f

        /**
         * Largeur libre minimale d'un intervalle (m) : le corps
         * (2 × [BODY_HALF_WIDTH_M]) plus la marge d'épaule
         * ([Guidance.SHOULDER_MARGIN_M]) — 0,80 m.
         */
        const val PASS_WIDTH_M = 2f * BODY_HALF_WIDTH_M + Guidance.SHOULDER_MARGIN_M

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
