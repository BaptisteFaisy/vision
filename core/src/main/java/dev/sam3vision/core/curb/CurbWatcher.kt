package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal trottoir d'une frame : [detected] quand un bord à monter est dans le
 * couloir marché à [CurbWatcher.CURB_METERS] ou moins, [meters] sa distance
 * (la plus proche en cas de doublons).
 */
data class CurbSignal(
    val detected: Boolean,
    val meters: Float?,
) {
    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): CurbSignal = CurbSignal(detected = false, meters = null)

        /**
         * Signal des lectures affinées ([meters] sténopé ou profondeur) : un
         * label [CurbConcepts] dans le couloir ([PositionText.SIDE_EDGE]),
         * bas de boîte en bande basse (le champ proche) et distance sous le
         * seuil. Boîte illisible ou dimensions nulles = ignoré, pas d'exception.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): CurbSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var best: Float? = null
            for (reading in readings) {
                val meters = nearCurbMeters(reading, frameW, frameH) ?: continue
                if (best == null || meters < best) best = meters
            }
            return if (best == null) none() else CurbSignal(detected = true, meters = best)
        }

        /** Distance du bord si [reading] en est un proche, `null` sinon. */
        private fun nearCurbMeters(reading: DistanceReading, frameW: Int, frameH: Int): Float? {
            if (!CurbConcepts.isCurb(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            if (meters > CurbWatcher.CURB_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            if (box[3] / frameH < CurbWatcher.LOWER_BAND_FRACTION) return null
            return meters
        }
    }
}

/**
 * Ordre d'annonce « trottoir devant » : [phrase] dans la langue de la voix,
 * [meters] la distance du signal qui l'a déclenché (journal, réglages).
 */
data class CurbCue(
    val phrase: String,
    val meters: Float?,
)

/**
 * Phrases « trottoir, levez la jambe », FR/EN comme les positions
 * (`PositionText`). Liste fermée d'une seule phrase : le préchauffage
 * hors-ligne n'a qu'un clip à générer si la phrase rejoint le mode traversée.
 */
object CurbText {
    /** « Trottoir, levez la jambe. » / « Curb, lift your foot. » selon [language]. */
    fun phrase(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Trottoir, levez la jambe."
        SpeechLanguage.En -> "Curb, lift your foot."
    }
}

/**
 * Lissage temporel du [CurbSignal], miroir de `StairWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs, puis
 * silence pendant [cooldownMs] (un trottoir ne bouge pas).
 *
 * Limites assumées, à recalibrer sur captures du téléphone (priors, comme le
 * veto trafic) : `sidewalk` segmente le plan entier, et marcher **sur** un
 * trottoir peut en remplir la bande basse — une fausse annonce « levez la
 * jambe » à un utilisateur aveugle est une décision de sécurité, pas un
 * défaut : seuils à valider sur appareil avant usage réel.
 *
 * Pur JVM et testé. Urgence : l'appelant parle avec `urgent = true` (coupe la
 * lecture en cours, comme le couloir bouché).
 */
class CurbWatcher(
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
    fun onSignal(signal: CurbSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.Fr): CurbCue? {
        if (!signal.detected) {
            hits = 0
            return null
        }
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        hits = 0
        return CurbCue(CurbText.phrase(language), signal.meters)
    }

    /** Oublie le compte et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 3

        /** Silence entre deux annonces (le trottoir ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), un bord ne fait pas lever la jambe. */
        const val CURB_METERS = 1f

        /** Bas de boîte minimal (fraction de hauteur) : le champ proche. */
        const val LOWER_BAND_FRACTION = 0.6f

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
