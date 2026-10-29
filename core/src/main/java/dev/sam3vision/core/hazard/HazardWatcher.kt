package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal mobilier d'une frame : [detected] quand du mobilier suivi est dans
 * le couloir marché à [HazardWatcher.HAZARD_METERS] ou moins, [meters] sa
 * distance (la plus proche en cas de doublons), [label] son libellé SAM brut
 * (la phrase y lit le nom parlé).
 */
data class HazardSignal(
    val detected: Boolean,
    val meters: Float?,
    val label: String?,
) {
    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): HazardSignal = HazardSignal(detected = false, meters = null, label = null)

        /**
         * Signal des lectures affinées : un label [HazardConcepts] dans le
         * couloir ([PositionText.SIDE_EDGE]), mesuré sous le seuil. Le plus
         * proche gagne ; boîte illisible ou dimensions nulles = ignoré.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): HazardSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var best: Pair<Float, String>? = null
            for (reading in readings) {
                val hit = nearHazard(reading, frameW) ?: continue
                if (best == null || hit.first < best.first) best = hit
            }
            return if (best == null) none()
            else HazardSignal(detected = true, meters = best.first, label = best.second)
        }

        /** Distance + libellé si [reading] est du mobilier proche, `null` sinon. */
        private fun nearHazard(reading: DistanceReading, frameW: Int): Pair<Float, String>? {
            if (!HazardConcepts.isHazard(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            if (meters > HazardWatcher.HAZARD_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            return meters to reading.label
        }
    }
}

/**
 * Ordre d'annonce « attention … » : [phrase] dans la langue de la voix,
 * [meters] la distance du signal qui l'a déclenché (journal, réglages).
 */
data class HazardCue(
    val phrase: String,
    val meters: Float?,
)

/**
 * Phrases « Attention … », FR/EN comme les positions (`PositionText`) :
 * « Attention à la barrière. » / « Attention, barrier. » — une info de
 * description, jamais une consigne (l'ordre user : iSee décrit, en douceur).
 */
object HazardText {
    /** « Attention … » pour [name] selon [language]. */
    fun phrase(name: HazardConcepts.HazardName, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Attention ${name.fr}."
        SpeechLanguage.En -> "Attention, ${name.en}."
    }
}

/**
 * Lissage temporel du [HazardSignal], miroir de `StairWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs, puis
 * silence pendant [cooldownMs] (le mobilier ne bouge pas).
 *
 * Pur JVM et testé. Douceur : l'appelant parle avec `urgent = false` (ne
 * coupe rien, attend le silence) — seule l'urgence (stop) coupe.
 */
class HazardWatcher(
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
    fun onSignal(signal: HazardSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.Fr): HazardCue? {
        val name = signal.label?.let(HazardConcepts::nameOf)
        if (!signal.detected || name == null) {
            hits = 0
            return null
        }
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        hits = 0
        return HazardCue(HazardText.phrase(name, language), signal.meters)
    }

    /** Oublie le compte et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces (le mobilier ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), le mobilier ne fait pas parler. */
        const val HAZARD_METERS = 3f

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
