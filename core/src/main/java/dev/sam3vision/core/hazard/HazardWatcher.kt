package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Mobilier de repli d'une frame : le meilleur non-personne quand le signal
 * retient une personne (la personne parle d'abord, P3 avant P4, puis la
 * barrière suit au lieu d'attendre son départ).
 */
data class HazardAlt(
    val meters: Float?,
    val label: String?,
    val centerX: Float? = null,
)

/**
 * Signal danger d'une frame : [detected] quand une personne ou du mobilier
 * suivi est dans le couloir marché à [HazardWatcher.HAZARD_METERS] ou moins
 * ([HazardWatcher.PERSON_METERS] pour une personne), [meters] sa distance,
 * [label] son libellé SAM brut (la phrase y lit le nom parlé), [centerX]
 * son centre normalisé (l'heure du cadran, `null` = inconnue). La personne
 * prime : présente, elle est retenue même si une barrière est plus proche
 * ([alt] la garde en repli) ; sinon le plus proche gagne. Le poteau ne
 * parle plus (ordre user du 01/11) : reconnu mais muet, jamais retenu.
 */
data class HazardSignal(
    val detected: Boolean,
    val meters: Float?,
    val label: String?,
    val centerX: Float? = null,
    val alt: HazardAlt? = null,
    /** Piste de la personne retenue (voir [HazardCue.trackId] ; `null` sans suivi). */
    val trackId: Int? = null,
) {
    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): HazardSignal = HazardSignal(detected = false, meters = null, label = null)

        /**
         * Signal des lectures affinées : un label parlé [HazardConcepts] dans
         * le couloir ([PositionText.SIDE_EDGE]) — plein axe pour une personne
         * imminente ([HazardWatcher.PERSON_FAST_METERS], le proche percute
         * même au bord du cadre) — mesuré sous le seuil. La personne
         * prime sur tout non-personne (P3 avant P4, gardé en [HazardSignal.alt]) ;
         * à sorte égale le plus proche gagne ; boîte illisible ou dimensions
         * nulles = ignoré.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): HazardSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var person: Hit? = null
            var other: Hit? = null
            for (reading in readings) {
                val hit = nearHazard(reading, frameW) ?: continue
                if (HazardConcepts.isPerson(reading.label)) {
                    if (person == null || hit.meters < person.meters) person = hit
                } else {
                    if (other == null || hit.meters < other.meters) other = hit
                }
            }
            val best = person ?: other ?: return none()
            val alt = if (person != null) other?.let { HazardAlt(it.meters, it.label, it.centerX) } else null
            return HazardSignal(
                detected = true, meters = best.meters, label = best.label,
                centerX = best.centerX, alt = alt, trackId = best.trackId,
            )
        }

        /** Danger proche candidat : distance, libellé et centre normalisé. */
        private data class Hit(val meters: Float, val label: String, val centerX: Float, val trackId: Int?)

        /** Candidat si [reading] est un danger parlé proche, `null` sinon. */
        private fun nearHazard(reading: DistanceReading, frameW: Int): Hit? {
            if (!HazardConcepts.isSpoken(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            val person = HazardConcepts.isPerson(reading.label)
            val limit = if (person) HazardWatcher.PERSON_METERS
            else HazardWatcher.HAZARD_METERS
            if (meters > limit) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            // Personne imminente : plein axe — à ≤ 2 m, le bord du cadre percute autant que le centre.
            val nearPerson = person && meters <= HazardWatcher.PERSON_FAST_METERS
            if (!nearPerson && (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE)) return null
            val trackId = reading.trackId.takeIf { it != DistanceMeter.UNTRACKED_ID }
            return Hit(meters, reading.label, centerX, trackId)
        }
    }
}

/**
 * Ordre d'annonce « attention … » : [phrase] dans la langue de la voix,
 * [meters] la distance du signal qui l'a déclenché (journal, réglages),
 * [urgent] pour la seule personne imminente (son mot seul d'évitement,
 * « Droite. »/« Gauche. », part en clip `guide_*`, jamais fusionné),
 * [kind] sa sorte (l'arbitre voix y lit le rang : personne P3, barrière
 * P4, divers avec le reste), [safetyPhrase] l'alerte immédiate lue par
 * l'arbitre (`null` pour la personne : le mot imminent part en clip, la
 * fusion casserait le clipId — la barrière reste douce).
 */
data class HazardCue(
    val phrase: String,
    val meters: Float?,
    val urgent: Boolean = false,
    val kind: HazardKind = HazardKind.OTHER,
    val safetyPhrase: String? = null,
    /**
     * Personne suivie que cette annonce concerne (`null` : pas de piste).
     * La couche voix s'en sert comme clé de cadence : une annonce par
     * **personne**, pas une par côté toutes les 20 s (audit foule du 01/11).
     */
    val trackId: Int? = null,
)

/**
 * Phrases « Attention … », FR/EN comme les positions (`PositionText`) :
 * « Attention à la barrière, à 2 heures. » / « Attention, barrier, at 2
 * o'clock. » — une info de description, jamais une consigne (l'ordre user :
 * iSee décrit, en douceur, au cadran des aveugles) — sauf la personne,
 * qui dit aussi comment l'éviter (ordre user du 01/11, voir [personPhrase]).
 */
object HazardText {
    /** Axe marché : en deçà, la personne est à gauche — on passe à droite. */
    private const val AXIS_MID = 0.5f

    /**
     * « Attention à la personne, à 2 heures, passez à gauche. » : la personne
     * douce (2-4 m) dit aussi comment l'éviter — le côté conseillé est
     * l'opposé de la personne (personne à gauche de l'axe = on passe à
     * droite). Axe pile ou heure inconnue = droite par défaut (prior, à
     * valider rue : on serre à droite). Personne seule (les autres dangers
     * restent descriptifs, voir [phrase]) : l'imminente (≤ 2 m) dit le mot
     * seul d'évitement en clip, voir [HazardWatcher].
     */
    fun personPhrase(
        name: HazardConcepts.HazardName,
        language: SpeechLanguage,
        centerX: Float? = null,
    ): String {
        val situated = phrase(name, language, centerX).dropLast(1)
        return "$situated, ${avoidText(centerX, language)}."
    }

    /**
     * Côté d'évitement conseillé : l'opposé de la personne (personne à
     * gauche de l'axe = on passe à droite). Axe pile ou heure inconnue =
     * droite par défaut (prior, à valider rue : on serre à droite). Seule
     * source du côté, lue par la phrase douce ([personPhrase]) comme par
     * le mot imminent ([HazardWatcher]).
     */
    fun avoidSide(centerX: Float?): SteeringCap =
        if (centerX == null || centerX <= AXIS_MID) SteeringCap.Right else SteeringCap.Left

    /** Consigne d'évitement : le côté opposé à la personne (droite par défaut). */
    private fun avoidText(centerX: Float?, language: SpeechLanguage): String {
        val right = avoidSide(centerX) == SteeringCap.Right
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
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs **de la
 * même sorte** (personne ou non-personne : l'alternance ne confirme rien),
 * puis silence pendant [cooldownMs] (dit-une-fois, cooldown partagé :
 * pas de bavardage même si les humains bougent).
 *
 * Pur JVM et testé. Doux par défaut (`urgent = false`, attend le silence,
 * [HazardText.personPhrase] avec évitement) ; la personne imminente (à
 * [PERSON_FAST_METERS] ou moins) dit le mot seul d'évitement dès la 1re
 * frame (« Droite. »/« Gauche. », clips `guide_*`, départ < 100 ms),
 * répété toutes les [IMMINENT_REPEAT_MS] tant qu'imminente, sans consommer
 * le cooldown doux (la barrière suit sans attendre) et sans passer par la
 * voix de sécurité. Personne douce déjà dite : le repli ([HazardSignal.alt])
 * parle à sa place au lieu d'attendre son départ — mais l'imminente
 * contourne ce verrou (doux-puis-approche parle, sinon silence critique).
 */
class HazardWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    /** Personnes consécutives en cours (autre chose remet à zéro). */
    private var personHits: Int = 0

    /** Non-personnes consécutives en cours (une personne remet à zéro). */
    private var otherHits: Int = 0

    /** Instant de la dernière annonce douce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /** Personne déjà dite, en attente de ré-armement ([PERSON_REARM_FRAMES] sans personne). */
    private var personSpoken: Boolean = false

    /** Frames consécutives sans personne détectée (un clignotement ne ré-arme pas). */
    private var personQuiet: Int = 0

    /** Dernier mot imminent parlé, `null` si aucun (porte de répétition). */
    private var lastImminentWord: String? = null

    /** Instant du dernier mot imminent, [NO_INSTANT] si aucun. */
    private var lastImminentMs: Long = NO_INSTANT

    /** Dernière annonce douce, `null` si aucune (le mot imminent ne compte pas). */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /** Candidat parlé de la frame : sorte, nom, distance et centre (principal ou repli). */
    private data class Candidate(
        val kind: HazardKind,
        val name: HazardConcepts.HazardName,
        val meters: Float?,
        val centerX: Float?,
        val trackId: Int? = null,
    )

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint hors cooldown, `null` sinon.
     */
    fun onSignal(signal: HazardSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): HazardCue? {
        trackPerson(signal)
        val candidate = candidateOf(signal) ?: run { personHits = 0; otherHits = 0; return null }
        return if (candidate.kind == HazardKind.PERSON) onPerson(candidate, nowMs, language)
        else onOther(candidate, nowMs, language)
    }

    /** Suit la personne (principal ou repli) : vue, ou ré-armée après [PERSON_REARM_FRAMES] sans. */
    private fun trackPerson(signal: HazardSignal) {
        val seen = signal.detected && (isPersonLabel(signal.label) || isPersonLabel(signal.alt?.label))
        if (seen) {
            personQuiet = 0
        } else {
            personQuiet = (personQuiet + 1).coerceAtMost(PERSON_REARM_FRAMES)
            if (personQuiet >= PERSON_REARM_FRAMES) personSpoken = false
        }
    }

    /** Vrai si [label] nomme une personne (`null` = non). */
    private fun isPersonLabel(label: String?): Boolean = label?.let(HazardConcepts::isPerson) == true

    /** Vrai si [meters] est une personne imminente (à [PERSON_FAST_METERS] ou moins). */
    private fun isImminent(meters: Float?): Boolean = meters != null && meters <= PERSON_FAST_METERS

    /**
     * Candidat de la frame : le signal retenu, sauf personne douce déjà
     * dite (le repli parle alors à sa place), `null` si rien à confirmer.
     * La personne imminente contourne le verrou dit-une-fois (doux puis
     * approche = le mot parle, sinon silence critique). Le principal comme
     * le repli portent [HazardConcepts.isSpoken] : un vieux signal poteau
     * reste muet.
     */
    private fun candidateOf(signal: HazardSignal): Candidate? {
        if (!signal.detected) return null
        val label = signal.label ?: return null
        if (!HazardConcepts.isSpoken(label)) return null
        val name = HazardConcepts.nameOf(label) ?: return null
        if (HazardConcepts.isPerson(label) && personSpoken && !isImminent(signal.meters)) {
            val alt = signal.alt ?: return null
            val altLabel = alt.label ?: return null
            if (!HazardConcepts.isSpoken(altLabel)) return null
            val altName = HazardConcepts.nameOf(altLabel) ?: return null
            return Candidate(HazardConcepts.kindOf(altLabel), altName, alt.meters, alt.centerX,
                trackId = signal.trackId)
        }
        return Candidate(HazardConcepts.kindOf(label), name, signal.meters, signal.centerX,
            trackId = signal.trackId)
    }

    /**
     * Personne : l'imminente dit le mot seul d'évitement dès la 1re frame
     * (clip `guide_*`, répété toutes les [IMMINENT_REPEAT_MS], immédiat au
     * changement de mot) ; sinon douce confirmée, dite une fois.
     */
    private fun onPerson(candidate: Candidate, nowMs: Long, language: SpeechLanguage): HazardCue? {
        otherHits = 0
        val meters = candidate.meters
        if (isImminent(meters)) {
            val word = GuidanceWord.fromCap(HazardText.avoidSide(candidate.centerX)).text(language)
            if (word == lastImminentWord && nowMs - lastImminentMs < IMMINENT_REPEAT_MS) return null
            lastImminentWord = word
            lastImminentMs = nowMs
            personSpoken = true
            personQuiet = 0
            personHits = 0
            return HazardCue(word, meters, urgent = true, kind = HazardKind.PERSON, trackId = candidate.trackId)
        }
        personHits++
        if (personHits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        personHits = 0
        personSpoken = true
        personQuiet = 0
        return HazardCue(
            HazardText.personPhrase(candidate.name, language, candidate.centerX),
            meters, kind = HazardKind.PERSON, trackId = candidate.trackId,
        )
    }

    /** Non-personne : doux confirmé, cooldown partagé avec la personne douce. */
    private fun onOther(candidate: Candidate, nowMs: Long, language: SpeechLanguage): HazardCue? {
        personHits = 0
        otherHits++
        if (otherHits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        lastCueAtMs = nowMs
        otherHits = 0
        return HazardCue(
            HazardText.phrase(candidate.name, language, candidate.centerX),
            candidate.meters, kind = candidate.kind, trackId = candidate.trackId,
        )
    }

    /** Oublie les comptes, l'annonce et le mot imminent (nouvelle séance de direct). */
    fun reset() {
        personHits = 0
        otherHits = 0
        lastCueAtMs = NO_INSTANT
        personSpoken = false
        personQuiet = 0
        lastImminentWord = null
        lastImminentMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces douces (dit-une-fois : pas de bavardage). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), le mobilier ne fait pas parler. */
        const val HAZARD_METERS = 3f

        /** Au-delà de cette distance (m), une personne ne fait pas parler (ordre user : 4 m). */
        const val PERSON_METERS = 4f

        /** Personne à cette distance (m) ou moins : mot seul dès la 1re frame, urgent. */
        const val PERSON_FAST_METERS = 2f

        /**
         * Répétition du mot imminent : il reparle toutes les 300 ms tant que
         * la personne reste imminente (miroir du `HEARTBEAT_MS` traversée —
         * le core ne voit pas l'app, la valeur est dupliquée, pas partagée).
         */
        const val IMMINENT_REPEAT_MS = 300L

        /** Frames sans personne avant de ré-armer l'annonce (un clignotement ne ré-arme pas). */
        const val PERSON_REARM_FRAMES = 3

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
