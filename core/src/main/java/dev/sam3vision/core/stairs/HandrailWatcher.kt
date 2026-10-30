package dev.sam3vision.core.stairs

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal rampe d'une frame : [detected] quand une main courante suivie est
 * dans le couloir marché à [HandrailWatcher.HANDRAIL_METERS] ou moins,
 * [meters] sa distance (la plus proche en cas de doublons), [centerX] son
 * centre normalisé (l'heure du cadran, `null` = inconnue).
 */
data class HandrailSignal(
    val detected: Boolean,
    val meters: Float?,
    val centerX: Float? = null,
) {
    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): HandrailSignal = HandrailSignal(detected = false, meters = null)

        /**
         * Signal des lectures affinées : un label [HandrailConcepts] dans le
         * couloir ([PositionText.SIDE_EDGE]), mesuré sous le seuil. Le plus
         * proche gagne ; boîte illisible ou dimensions nulles = ignoré.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): HandrailSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var best: Hit? = null
            for (reading in readings) {
                val hit = nearHandrail(reading, frameW) ?: continue
                if (best == null || hit.meters < best.meters) best = hit
            }
            return if (best == null) none()
            else HandrailSignal(detected = true, meters = best.meters, centerX = best.centerX)
        }

        /** Rampe proche candidate : distance et centre normalisé. */
        private data class Hit(val meters: Float, val centerX: Float)

        /** Candidate si [reading] est une rampe proche, `null` sinon. */
        private fun nearHandrail(reading: DistanceReading, frameW: Int): Hit? {
            if (!HandrailConcepts.isHandrail(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            if (meters > HandrailWatcher.HANDRAIL_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            return Hit(meters, centerX)
        }
    }
}

/**
 * Ordre d'annonce « rampe » : [phrase] dans la langue de la voix, [meters]
 * la distance du signal qui l'a déclenché (journal, réglages).
 */
data class HandrailCue(
    val phrase: String,
    val meters: Float?,
)

/**
 * Phrases rampe, FR/EN comme les positions (`PositionText`) : une info de
 * navigation (« Rampe, à 2 heures. »), jamais une consigne — iSee décrit, en
 * douceur, au cadran des aveugles.
 */
object HandrailText {
    /** « Rampe, à 2 heures. » / « Handrail, at 2 o'clock. » (`null` = sans heure). */
    fun phrase(language: SpeechLanguage, centerX: Float? = null): String = when (language) {
        SpeechLanguage.Fr -> PositionText.withHour("Rampe", centerX, language)
        SpeechLanguage.En -> PositionText.withHour("Handrail", centerX, language)
    }
}

/**
 * Lissage temporel du [HandrailSignal], miroir de `HazardWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs, puis
 * silence pendant [cooldownMs] (la rampe ne bouge pas).
 *
 * Pur JVM et testé. Douceur : l'appelant parle avec `urgent = false` (ne
 * coupe rien, attend le silence) — seule l'urgence (stop) coupe.
 */
class HandrailWatcher(
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
     */
    fun onSignal(
        signal: HandrailSignal,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): HandrailCue? {
        if (!signal.detected) {
            hits = 0
            return null
        }
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        hits = 0
        return HandrailCue(HandrailText.phrase(language, signal.centerX), signal.meters)
    }

    /** Oublie le compte et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces (la rampe ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), la rampe ne fait pas parler (prior, miroir mobilier). */
        const val HANDRAIL_METERS = 3f

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
