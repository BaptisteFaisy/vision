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
         * couloir ([PositionText.SIDE_EDGE]) — plein axe pour un poteau
         * imminent ([HazardWatcher.POLE_FAST_METERS], le proche percute
         * même au bord du cadre) — mesuré sous le seuil. Le plus proche
         * gagne ; boîte illisible ou dimensions nulles = ignoré.
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
            val pole = HazardConcepts.isPole(reading.label)
            val limit = if (pole) HazardWatcher.POLE_METERS
            else HazardWatcher.HAZARD_METERS
            if (meters > limit) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            // Poteau imminent : plein axe — à ≤ 2 m, le bord du cadre percute autant que le centre.
            val nearPole = pole && meters <= HazardWatcher.POLE_FAST_METERS
            if (!nearPole && (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE)) return null
            return Hit(meters, reading.label, centerX)
        }
    }
}

/**
 * Ordre d'annonce « attention … » : [phrase] dans la langue de la voix,
 * [meters] la distance du signal qui l'a déclenché (journal, réglages),
 * [urgent] pour le seul poteau imminent (coupe la voix, sauf P1/P2 en cours —
 * voir `ProbeWiring.shouldCutForHazard`) — à honorer même en Traversée (P3 parle quand même).
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
 * iSee décrit, en douceur, au cadran des aveugles) — sauf le poteau,
* qui dit aussi comment l'éviter (ordre user du 31/10, voir [polePhrase]).
 */
object HazardText {
    /** Axe marché : en deçà, le poteau est à gauche — on passe à droite. */
    private const val AXIS_MID = 0.5f

    /**
     * « Attention au poteau, à 2 heures, passez à gauche. » : le poteau dit
     * aussi comment l'éviter — le côté conseillé est l'opposé du poteau
     * (poteau à gauche de l'axe = on passe à droite). Axe pile ou heure
     * inconnue = droite par défaut (prior, à valider rue : on serre à
     * droite). Doux comme imminent, poteau seul (les autres mobiliers
     * restent descriptifs, voir [phrase]).
     */
    fun polePhrase(
        name: HazardConcepts.HazardName,
        language: SpeechLanguage,
        centerX: Float? = null,
    ): String {
        val situated = phrase(name, language, centerX).dropLast(1)
        return "$situated, ${avoidText(centerX, language)}."
    }

    /** Consigne d'évitement : le côté opposé au poteau (droite par défaut). */
    private fun avoidText(centerX: Float?, language: SpeechLanguage): String {
        val right = centerX == null || centerX < AXIS_MID
        return when (language) {
            SpeechLanguage.Fr -> if (right) "passez à droite" else "passez à gauche"
            SpeechLanguage.En -> if (right) "pass on the right" else "pass on the left"
        }
    }

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
 * isolé ne parle pas, il faut [confirmFrames] positifs sur les
 * [CONFIRM_WINDOW] dernières frames (2/3 : les poteaux intermittents
 * parlent), puis silence ([poleCooldownMs] pour un poteau, [cooldownMs]
 * sinon — le mobilier ne bouge pas).
 *
 * Pur JVM et testé. Doux par défaut (`urgent = false`, attend le silence) ;
 * le poteau imminent (à [POLE_FAST_METERS] ou moins) sort urgent : il coupe,
 * sauf P1/P2 en cours (voir `ProbeWiring`) — et l'urgent reste à honorer
 * même en Traversée (P3 parle quand même, câblage `observeHazard` à ouvrir).
 * Le poteau dit aussi comment l'éviter ([HazardText.polePhrase]), doux comme imminent.
 */
class HazardWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val poleCooldownMs: Long = DEFAULT_POLE_COOLDOWN_MS,
) {
    /** Positifs des dernières frames ([CONFIRM_WINDOW] au plus : 2/3 confirment). */
    private val window: ArrayDeque<Boolean> = ArrayDeque()

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
            pushWindow(false)
            return null
        }
        if (pole && poleSpoken) {
            window.clear()
            return null
        }
        val meters = signal.meters
        if (pole && meters != null && meters <= POLE_FAST_METERS) {
            poleSpoken = true
            poleQuiet = 0
            lastCueAtMs = nowMs
            window.clear()
            return HazardCue(HazardText.polePhrase(name, language, signal.centerX), meters, urgent = true)
        }
        pushWindow(true)
        if (window.count { it } < confirmFrames.coerceIn(1, CONFIRM_WINDOW)) return null
        val cool = if (pole) poleCooldownMs else cooldownMs
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cool) return null
        lastCueAtMs = nowMs
        window.clear()
        if (pole) {
            poleSpoken = true
            poleQuiet = 0
        }
        val text = if (pole) HazardText.polePhrase(name, language, signal.centerX)
        else HazardText.phrase(name, language, signal.centerX)
        return HazardCue(text, signal.meters)
    }

    /** Pousse [positive] dans la fenêtre ([CONFIRM_WINDOW] dernières frames au plus). */
    private fun pushWindow(positive: Boolean) {
        window.addLast(positive)
        while (window.size > CONFIRM_WINDOW) window.removeFirst()
    }

    /** Oublie le compte et l'annonce (nouvelle séance de direct). */
    fun reset() {
        window.clear()
        lastCueAtMs = NO_INSTANT
        poleSpoken = false
        poleQuiet = 0
    }

    companion object {
        /** Positifs requis sur la fenêtre [CONFIRM_WINDOW] avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Fenêtre de confirmation : les dernières frames comptées (2/3 confirment). */
        const val CONFIRM_WINDOW = 3

        /** Silence entre deux annonces (le mobilier ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Silence entre deux annonces de poteau (4 s : le marcheur avance, un poteau en cache un autre). */
        const val DEFAULT_POLE_COOLDOWN_MS = 4_000L

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
