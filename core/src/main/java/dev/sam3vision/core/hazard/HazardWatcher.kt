package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal mobilier d'une frame : [detected] quand du mobilier suivi est dans
 * le couloir marché à [HazardWatcher.HAZARD_METERS] ou moins
 * ([HazardWatcher.POLE_METERS] pour un poteau), [meters] sa distance (la plus
 * proche en cas de doublons), [label] son libellé SAM brut (la phrase y lit le
 * nom parlé), [centerX] son centre normalisé (l'heure du cadran,
 * `null` = inconnue).
 */
data class HazardSignal(
    val detected: Boolean,
    val meters: Float?,
    val label: String?,
    val centerX: Float? = null,
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
            var best: Hit? = null
            for (reading in readings) {
                val hit = nearHazard(reading, frameW) ?: continue
                if (best == null || hit.meters < best.meters) best = hit
            }
            return if (best == null) none()
            else HazardSignal(detected = true, meters = best.meters, label = best.label, centerX = best.centerX)
        }

        /** Mobilier proche candidat : distance, libellé et centre normalisé. */
        private data class Hit(val meters: Float, val label: String, val centerX: Float)

        /** Candidat si [reading] est du mobilier proche, `null` sinon. */
        private fun nearHazard(reading: DistanceReading, frameW: Int): Hit? {
            if (!HazardConcepts.isHazard(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            val limit = if (HazardConcepts.isPole(reading.label)) HazardWatcher.POLE_METERS
            else HazardWatcher.HAZARD_METERS
            if (meters > limit) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            return Hit(meters, reading.label, centerX)
        }
    }
}

/**
 * Ordre d'annonce « attention … » : [phrase] dans la langue de la voix,
 * [meters] la distance du signal qui l'a déclenché (journal, réglages),
 * [urgent] pour le seul poteau imminent (coupe la voix, sauf P1/P2 en cours —
 * voir `ProbeWiring.shouldCutForHazard`).
 */
data class HazardCue(
    val phrase: String,
    val meters: Float?,
    val urgent: Boolean = false,
)

/**
 * Phrases « Attention … », FR/EN comme les positions (`PositionText`) :
 * « Attention à la barrière, à 2 heures. » / « Attention, barrier, at 2
 * o'clock. » — une info de description, jamais une consigne (l'ordre user :
 * iSee décrit, en douceur, au cadran des aveugles).
 */
object HazardText {
    /** « Attention … » pour [name] selon [language], située à [centerX] (`null` = sans heure). */
    fun phrase(
        name: HazardConcepts.HazardName,
        language: SpeechLanguage,
        centerX: Float? = null,
    ): String = when (language) {
        SpeechLanguage.Fr -> PositionText.withHour("Attention ${name.fr}", centerX, language)
        SpeechLanguage.En -> PositionText.withHour("Attention, ${name.en}", centerX, language)
    }
}

/**
 * Lissage temporel du [HazardSignal], miroir de `StairWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs, puis
 * silence pendant [cooldownMs] (le mobilier ne bouge pas).
 *
 * Pur JVM et testé. Doux par défaut (`urgent = false`, attend le silence) ;
 * le poteau imminent (à [POLE_FAST_METERS] ou moins) sort urgent : il coupe,
 * sauf P1/P2 en cours (voir `ProbeWiring`).
 */
class HazardWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    /** Positifs consécutifs en cours (un négatif remet à zéro). */
    private var hits: Int = 0

    /** Instant de la dernière annonce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /** Poteau déjà dit, en attente de ré-armement ([POLE_REARM_FRAMES] sans poteau). */
    private var poleSpoken: Boolean = false

    /** Frames consécutives sans poteau détecté (un clignotement ne ré-arme pas). */
    private var poleQuiet: Int = 0

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint hors cooldown, `null` sinon.
     */
    fun onSignal(signal: HazardSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): HazardCue? {
        val name = signal.label?.let(HazardConcepts::nameOf)
        val pole = signal.detected && signal.label?.let(HazardConcepts::isPole) == true
        if (pole) {
            poleQuiet = 0
        } else {
            poleQuiet = (poleQuiet + 1).coerceAtMost(POLE_REARM_FRAMES)
            if (poleQuiet >= POLE_REARM_FRAMES) poleSpoken = false
        }
        if (!signal.detected || name == null) {
            hits = 0
            return null
        }
        if (pole && poleSpoken) {
            hits = 0
            return null
        }
        val meters = signal.meters
        if (pole && meters != null && meters <= POLE_FAST_METERS) {
            poleSpoken = true
            poleQuiet = 0
            lastCueAtMs = nowMs
            hits = 0
            return HazardCue(HazardText.phrase(name, language, signal.centerX), meters, urgent = true)
        }
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        hits = 0
        if (pole) {
            poleSpoken = true
            poleQuiet = 0
        }
        return HazardCue(HazardText.phrase(name, language, signal.centerX), signal.meters)
    }

    /** Oublie le compte et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        lastCueAtMs = NO_INSTANT
        poleSpoken = false
        poleQuiet = 0
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces (le mobilier ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), le mobilier ne fait pas parler. */
        const val HAZARD_METERS = 3f

        /** Au-delà de cette distance (m), un poteau ne fait pas parler (ordre user : 4 m). */
        const val POLE_METERS = 4f

        /** Poteau à cette distance (m) ou moins : annonce dès la 1re frame, urgente. */
        const val POLE_FAST_METERS = 2f

        /** Frames sans poteau avant de ré-armer l'annonce (un clignotement ne ré-arme pas). */
        const val POLE_REARM_FRAMES = 3

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
