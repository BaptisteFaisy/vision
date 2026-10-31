package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Mobilier de repli d'une frame : le meilleur non-poteau quand le signal
 * retient un poteau (le poteau parle d'abord, P3 avant P4, puis la barrière
 * suit au lieu d'attendre la disparition du poteau).
 */
data class HazardAlt(
    val meters: Float?,
    val label: String?,
    val centerX: Float? = null,
)

/**
 * Signal mobilier d'une frame : [detected] quand du mobilier suivi est dans
 * le couloir marché à [HazardWatcher.HAZARD_METERS] ou moins
 * ([HazardWatcher.POLE_METERS] pour un poteau), [meters] sa distance, [label]
 * son libellé SAM brut (la phrase y lit le nom parlé), [centerX] son centre
 * normalisé (l'heure du cadran, `null` = inconnue). Le poteau prime : présent,
 * il est retenu même si une barrière est plus proche ([alt] la garde en
 * repli) ; sinon le plus proche gagne.
 */
data class HazardSignal(
    val detected: Boolean,
    val meters: Float?,
    val label: String?,
    val centerX: Float? = null,
    val alt: HazardAlt? = null,
) {
    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): HazardSignal = HazardSignal(detected = false, meters = null, label = null)

        /**
         * Signal des lectures affinées : un label [HazardConcepts] dans le
         * couloir ([PositionText.SIDE_EDGE]) — plein axe pour un poteau
         * imminent ([HazardWatcher.POLE_FAST_METERS], le proche percute
         * même au bord du cadre) — mesuré sous le seuil. Le poteau
         * prime sur tout non-poteau (P3 avant P4, gardé en [HazardSignal.alt]) ;
         * à sorte égale le plus proche gagne ; boîte illisible ou dimensions
         * nulles = ignoré.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): HazardSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var pole: Hit? = null
            var other: Hit? = null
            for (reading in readings) {
                val hit = nearHazard(reading, frameW) ?: continue
                if (HazardConcepts.isPole(reading.label)) {
                    if (pole == null || hit.meters < pole.meters) pole = hit
                } else {
                    if (other == null || hit.meters < other.meters) other = hit
                }
            }
            val best = pole ?: other ?: return none()
            val alt = if (pole != null) other?.let { HazardAlt(it.meters, it.label, it.centerX) } else null
            return HazardSignal(
                detected = true, meters = best.meters, label = best.label,
                centerX = best.centerX, alt = alt,
            )
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
 * [urgent] pour le seul poteau imminent, [kind] sa sorte (l'arbitre voix y
 * lit le rang : poteau P3, barrière P4, divers avec le reste),
 * [safetyPhrase] l'alerte immédiate (consigne, jamais de « Stop » hors
 * Traversée) pour le seul poteau imminent (`null` sinon — la barrière
 * reste douce).
 */
data class HazardCue(
    val phrase: String,
    val meters: Float?,
    val urgent: Boolean = false,
    val kind: HazardKind = HazardKind.OTHER,
    val safetyPhrase: String? = null,
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

    /**
     * Alerte immédiate du poteau imminent : consigne courte + évitement, sans
     * heure (à un pas, le côté prime sur le cadran) et sans « Stop » (§ 22 :
     * le stop ne se dit qu'en Traversée). Parlée par la voix de sécurité,
     * qui coupe tout, dictée du user comprise.
     */
    fun safetyPolePhrase(language: SpeechLanguage, centerX: Float? = null): String = when (language) {
        SpeechLanguage.Fr -> "Poteau, ${avoidText(centerX, language)}."
        SpeechLanguage.En -> "Pole, ${avoidText(centerX, language)}."
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
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs **de la
 * même sorte** (poteau ou non-poteau : l'alternance ne confirme rien), puis
 * silence pendant [cooldownMs] (le mobilier ne bouge pas, cooldown partagé).
 *
 * Pur JVM et testé. Doux par défaut (`urgent = false`, attend le silence) ;
 * le poteau imminent (à [POLE_FAST_METERS] ou moins) parle dès la 1re frame
 * en alerte de sécurité ([HazardCue.safetyPhrase], sans consommer le cooldown
 * doux : la barrière suit sans attendre). Le poteau dit aussi comment
 * l'éviter ([HazardText.polePhrase]), doux comme imminent. Poteau déjà dit :
 * le repli ([HazardSignal.alt]) parle à sa place au lieu d'attendre la
 * disparition du poteau.
 */
class HazardWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    /** Poteaux consécutifs en cours (autre chose remet à zéro). */
    private var poleHits: Int = 0

    /** Non-poteaux consécutifs en cours (un poteau remet à zéro). */
    private var otherHits: Int = 0

    /** Instant de la dernière annonce douce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /** Poteau déjà dit, en attente de ré-armement ([POLE_REARM_FRAMES] sans poteau). */
    private var poleSpoken: Boolean = false

    /** Frames consécutives sans poteau détecté (un clignotement ne ré-arme pas). */
    private var poleQuiet: Int = 0

    /** Dernière annonce douce, `null` si aucune (l'imminent sécurité ne compte pas). */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /** Candidat parlé de la frame : sorte, nom, distance et centre (principal ou repli). */
    private data class Candidate(
        val kind: HazardKind,
        val name: HazardConcepts.HazardName,
        val meters: Float?,
        val centerX: Float?,
    )

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint hors cooldown, `null` sinon.
     */
    fun onSignal(signal: HazardSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): HazardCue? {
        trackPole(signal)
        val candidate = candidateOf(signal) ?: run { poleHits = 0; otherHits = 0; return null }
        return if (candidate.kind == HazardKind.POLE) onPole(candidate, nowMs, language)
        else onOther(candidate, nowMs, language)
    }

    /** Suit le poteau (principal ou repli) : vu, ou ré-armé après [POLE_REARM_FRAMES] sans. */
    private fun trackPole(signal: HazardSignal) {
        val seen = signal.detected && (isPoleLabel(signal.label) || isPoleLabel(signal.alt?.label))
        if (seen) {
            poleQuiet = 0
        } else {
            poleQuiet = (poleQuiet + 1).coerceAtMost(POLE_REARM_FRAMES)
            if (poleQuiet >= POLE_REARM_FRAMES) poleSpoken = false
        }
    }

    /** Vrai si [label] nomme un poteau (`null` = non). */
    private fun isPoleLabel(label: String?): Boolean = label?.let(HazardConcepts::isPole) == true

    /**
     * Candidat de la frame : le signal retenu, sauf poteau déjà dit (le repli
     * parle alors à sa place), `null` si rien à confirmer.
     */
    private fun candidateOf(signal: HazardSignal): Candidate? {
        if (!signal.detected) return null
        val label = signal.label ?: return null
        val name = HazardConcepts.nameOf(label) ?: return null
        if (HazardConcepts.isPole(label) && poleSpoken) {
            val alt = signal.alt ?: return null
            val altLabel = alt.label ?: return null
            val altName = HazardConcepts.nameOf(altLabel) ?: return null
            return Candidate(HazardConcepts.kindOf(altLabel), altName, alt.meters, alt.centerX)
        }
        return Candidate(HazardConcepts.kindOf(label), name, signal.meters, signal.centerX)
    }

    /** Poteau : imminent dès la 1re frame (sécurité), sinon doux confirmé, dit une fois. */
    private fun onPole(candidate: Candidate, nowMs: Long, language: SpeechLanguage): HazardCue? {
        otherHits = 0
        val meters = candidate.meters
        if (meters != null && meters <= POLE_FAST_METERS) {
            poleSpoken = true
            poleQuiet = 0
            poleHits = 0
            return HazardCue(
                HazardText.polePhrase(candidate.name, language, candidate.centerX),
                meters, urgent = true, kind = HazardKind.POLE,
                safetyPhrase = HazardText.safetyPolePhrase(language, candidate.centerX),
            )
        }
        poleHits++
        if (poleHits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        poleHits = 0
        poleSpoken = true
        poleQuiet = 0
        return HazardCue(
            HazardText.polePhrase(candidate.name, language, candidate.centerX),
            meters, kind = HazardKind.POLE,
        )
    }

    /** Non-poteau : doux confirmé, cooldown partagé avec le poteau doux. */
    private fun onOther(candidate: Candidate, nowMs: Long, language: SpeechLanguage): HazardCue? {
        poleHits = 0
        otherHits++
        if (otherHits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        otherHits = 0
        return HazardCue(
            HazardText.phrase(candidate.name, language, candidate.centerX),
            candidate.meters, kind = candidate.kind,
        )
    }

    /** Oublie les comptes et l'annonce (nouvelle séance de direct). */
    fun reset() {
        poleHits = 0
        otherHits = 0
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
