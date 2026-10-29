package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal trottoir d'une frame : [detected] quand un bord à monter est dans le
 * couloir marché à [CurbWatcher.CURB_METERS] ou moins, [meters] sa distance
 * (la plus proche en cas de doublons), [centerX] son centre normalisé (l'heure
 * du cadran, `null` = inconnue).
 */
data class CurbSignal(
    val detected: Boolean,
    val meters: Float?,
    val centerX: Float? = null,
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
            var best: Pair<Float, Float>? = null
            for (reading in readings) {
                val hit = nearCurb(reading, frameW, frameH) ?: continue
                if (best == null || hit.first < best.first) best = hit
            }
            return if (best == null) none()
            else CurbSignal(detected = true, meters = best.first, centerX = best.second)
        }

        /** Distance + centre si [reading] est un bord proche, `null` sinon. */
        private fun nearCurb(reading: DistanceReading, frameW: Int, frameH: Int): Pair<Float, Float>? {
            if (!CurbConcepts.isCurb(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            if (meters > CurbWatcher.CURB_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            if (box[3] / frameH < CurbWatcher.LOWER_BAND_FRACTION) return null
            if (CurbConcepts.isGround(reading.label) && !nearEdge(box, frameH)) return null
            return meters to centerX
        }

        /**
         * Plan (`sidewalk`…) : bord proche ou sol sous les pieds ? Montée = le
         * plan commence au-dessus des pieds (bas sous [CurbWatcher.PLANE_BOTTOM_MAX]) ;
         * descente = plan collé aux pieds mais borné en haut (haut au-dessus de
         * [CurbWatcher.PLANE_TOP_MIN]) ; sinon c'est le sol sur lequel on marche.
         * Seuils : priors à valider sur captures téléphone.
         */
        private fun nearEdge(box: List<Float>, frameH: Int): Boolean {
            if (box[3] / frameH < CurbWatcher.PLANE_BOTTOM_MAX) return true
            return box[1] / frameH >= CurbWatcher.PLANE_TOP_MIN
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
 * Phrases « Attention à la marche », FR/EN comme les positions
 * (`PositionText`). Liste fermée d'une seule phrase : le préchauffage
 * hors-ligne n'a qu'un clip à générer si la phrase rejoint le mode traversée.
 */
object CurbText {
    /** « Attention à la marche, à midi. » / « Attention, step, at 12 o'clock. » (`null` = sans heure). */
    fun phrase(language: SpeechLanguage, centerX: Float? = null): String = when (language) {
        SpeechLanguage.Fr -> PositionText.withHour("Attention à la marche", centerX, language)
        SpeechLanguage.En -> PositionText.withHour("Attention, step", centerX, language)
    }
}

/**
 * Lissage temporel du [CurbSignal], miroir de `StairWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs, puis
 * silence pendant [cooldownMs] (un trottoir ne bouge pas).
 *
 * `sidewalk` segmente le plan entier, pas le bord : marcher **sur** un
 * trottoir ne doit pas annoncer « Attention à la marche » — le plan sous les
 * pieds (bas collé en bas de l'image, haut vers l'horizon) est ignoré, seuls
 * les plans à bord proche parlent (montée : bas décollé ; descente : haut
 * borné). Seuils priors, à valider sur captures du téléphone (comme le
 * veto trafic) : en cas de doute le bord parle, jamais l'inverse.
 *
 * Pur JVM et testé. Douceur : l'appelant parle avec `urgent = false` (ne
 * coupe rien, attend le silence) — seule l'urgence (stop) coupe.
 */
class CurbWatcher(
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
        return CurbCue(CurbText.phrase(language, signal.centerX), signal.meters)
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
        const val CURB_METERS = 3f

        /**
         * Bas de boîte minimal (fraction de hauteur) : à 3 m le bord tombe en
         * bande moyenne selon le tangage du téléphone — la bande suit pour ne
         * pas le rater (prior, à valider sur captures).
         */
        const val LOWER_BAND_FRACTION = 0.45f

        /** Bas de plan maximal (fraction) : au-delà, le plan touche les pieds (montée). */
        const val PLANE_BOTTOM_MAX = 0.95f

        /** Haut de plan minimal (fraction) : en deçà + pieds = descente, sinon sol. */
        const val PLANE_TOP_MIN = 0.5f

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
