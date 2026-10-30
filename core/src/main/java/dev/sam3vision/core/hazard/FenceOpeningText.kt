package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrases grillage, FR/EN comme les positions (`PositionText`) : une info de
 * description (« Grillage fermé devant vous. »), jamais une consigne — le
 * guidage dit où aller, ici on dit si la clôture s'ouvre.
 *
 * FR : « Grillage fermé devant vous. », « Portail ouvert … », « Ouverture …
 * », « Passage au milieu. » — le mot de secteur (devant, à gauche, à droite)
 * et la distance ([DistanceText.spokenShort]) situent déjà : le cadran tombe
 * par défaut, comme `PositionText.item` avec une distance connue. [centerX]
 * non `null` le rajoute (« …, à 10 heures. ») pour les appelants qui veulent
 * le cadran explicite.
 */
object FenceOpeningText {
    /**
     * Phrase pour [state] à [position] selon [language] : [meters] la distance
     * parlée ([DistanceText.spokenShort], absente = sans distance),
     * [centerX] le cadran explicite (`null` = les mots situent, défaut),
     * [isGate] le portail nommé, [middle] le passage entre deux pans.
     */
    fun phrase(
        state: FenceOpeningState,
        position: FenceOpeningPosition,
        language: SpeechLanguage,
        meters: Float? = null,
        centerX: Float? = null,
        isGate: Boolean = false,
        middle: Boolean = false,
    ): String {
        val base = when (language) {
            SpeechLanguage.Fr -> french(state, position, meters, isGate, middle)
            SpeechLanguage.En -> english(state, position, meters, isGate, middle)
        }
        return PositionText.withHour(base, centerX, language)
    }

    /** Base FR (sans point) : le fermé, le milieu, le portail nommé, sinon l'ouverture. */
    private fun french(
        state: FenceOpeningState,
        position: FenceOpeningPosition,
        meters: Float?,
        isGate: Boolean,
        middle: Boolean,
    ): String {
        if (state == FenceOpeningState.Closed) return "Grillage fermé devant vous"
        if (middle && !isGate) return "Passage au milieu"
        val distance = short(meters, SpeechLanguage.Fr)
        if (isGate) {
            val where = when (position) {
                FenceOpeningPosition.Ahead -> "devant vous"
                FenceOpeningPosition.Left -> "à gauche"
                FenceOpeningPosition.Right -> "à droite"
            }
            return if (distance == null) "Portail ouvert $where" else "Portail ouvert $where, à $distance"
        }
        val where = when (position) {
            FenceOpeningPosition.Ahead -> "devant"
            FenceOpeningPosition.Left -> "à gauche"
            FenceOpeningPosition.Right -> "à droite"
        }
        return if (distance == null) "Ouverture $where" else "Ouverture $where, à $distance"
    }

    /** Base EN (sans point), miroir FR : le cadran tombe aussi par défaut. */
    private fun english(
        state: FenceOpeningState,
        position: FenceOpeningPosition,
        meters: Float?,
        isGate: Boolean,
        middle: Boolean,
    ): String {
        if (state == FenceOpeningState.Closed) return "Fence closed ahead"
        if (middle && !isGate) return "Gap in the middle"
        val distance = short(meters, SpeechLanguage.En)
        if (isGate) {
            val where = when (position) {
                FenceOpeningPosition.Ahead -> "ahead"
                FenceOpeningPosition.Left -> "on the left"
                FenceOpeningPosition.Right -> "on the right"
            }
            return if (distance == null) "Gate open $where" else "Gate open $where, $distance away"
        }
        val where = when (position) {
            FenceOpeningPosition.Ahead -> "ahead"
            FenceOpeningPosition.Left -> "on the left"
            FenceOpeningPosition.Right -> "on the right"
        }
        return if (distance == null) "Opening $where" else "Opening $where, $distance away"
    }

    /** Distance courte parlée, `null` si elle n'est pas mesurable (la phrase s'en passe). */
    private fun short(meters: Float?, language: SpeechLanguage): String? =
        meters?.takeIf { it.isFinite() && it > 0f }?.let { DistanceText.spokenShort(it, language) }
}
