package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.ClearView
import dev.sam3vision.core.live.GuidanceSide
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.WidestGap
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal danger d'une frame : [detected] quand un corps guidé (personne,
 * enfant, poussette, fauteuil, chien — [HazardConcepts.isGuidedBody]) ou du
 * mobilier suivi est dans le couloir marché à [HazardWatcher.HAZARD_METERS]
 * ou moins ([HazardWatcher.PERSON_METERS] pour un corps, 15 m droit devant
 * pour les corps et le poteau — [HazardWatcher.PERSON_FAR_METERS] et
 * [HazardWatcher.POLE_FAR_METERS], ordre user du 01/11), [meters] sa
 * distance, [label] son libellé SAM brut (la phrase y lit le nom parlé),
 * [centerX] son centre normalisé (l'heure du cadran, `null` = inconnue).
 * Le corps prime : présent, il est retenu même si une barrière est plus
 * proche ; sinon le plus proche gagne. SPEC v4 : niveaux VoiceBus (2 les
 * corps, 4 grillages/murs/barrières, 7 poteaux et divers) ; la borne et le
 * portail ne sont plus reconnus. Sans repli : le mot continu ne cède
 * jamais la parole tant que le corps ou le poteau est là.
 */
data class HazardSignal(
    val detected: Boolean,
    val meters: Float?,
    val label: String?,
    val centerX: Float? = null,
    /** Piste de la personne retenue (voir [HazardCue.trackId] ; `null` sans suivi). */
    val trackId: Int? = null,
    /** Côté conseillé ([ClearPath]), `null` = l'opposé de [centerX] ([HazardText.avoidSide]). */
    val side: GuidanceSide? = null,
) {
    /**
     * Signal lu avec le chemin de la frame : [view] donne le côté, jugé sur toute
     * la scène, et son ordre fait parler un obstacle que la bande centrale ne voit
     * pas (bord de boîte dans le couloir, centre dehors). Un corps déjà retenu le
     * reste : le côté, lui, tient compte de tout ce qui est proche.
     */
    fun steeredBy(view: ClearView?): HazardSignal {
        if (view == null) return this
        if (detected) return copy(side = view.side)
        val threat = view.order?.threat ?: return this
        return HazardSignal(true, threat.meters, threat.label, threat.centerX, threat.trackId, view.side)
    }

    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): HazardSignal = HazardSignal(detected = false, meters = null, label = null)

        /**
         * Signal des lectures affinées : un label parlé [HazardConcepts] dans
         * le couloir ([PositionText.SIDE_EDGE]) — plein axe pour un corps
         * imminent ([HazardWatcher.PERSON_FAST_METERS], le proche percute
         * même au bord du cadre) — mesuré sous le seuil (15 m droit devant
         * pour les corps et le poteau, portée proche sinon). Le corps prime
         * sur tout le reste ; à sorte égale le plus proche gagne ; boîte
         * illisible ou dimensions nulles = ignoré.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): HazardSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var person: Hit? = null
            var other: Hit? = null
            for (reading in readings) {
                val hit = nearHazard(reading, frameW) ?: continue
                if (HazardConcepts.isGuidedBody(reading.label)) {
                    if (person == null || hit.meters < person.meters) person = hit
                } else {
                    if (other == null || hit.meters < other.meters) other = hit
                }
            }
            val best = person ?: other ?: return none()
            return HazardSignal(
                detected = true, meters = best.meters, label = best.label,
                centerX = best.centerX, trackId = best.trackId,
            )
        }

        /** Danger proche candidat : distance, libellé et centre normalisé. */
        private data class Hit(val meters: Float, val label: String, val centerX: Float, val trackId: Int?)

        /** Candidat si [reading] est un danger parlé proche, `null` sinon. */
        private fun nearHazard(reading: DistanceReading, frameW: Int): Hit? {
            if (!HazardConcepts.isSpoken(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            val inCorridor = centerX in PositionText.SIDE_EDGE..1f - PositionText.SIDE_EDGE
            val person = HazardConcepts.isGuidedBody(reading.label)
            // Corps imminent : plein axe — à ≤ 2 m, le bord du cadre percute autant que le centre.
            val nearPerson = person && meters <= HazardWatcher.PERSON_FAST_METERS
            if (!nearPerson && !inCorridor) return null
            if (meters > nearLimit(reading.label, person, inCorridor)) return null
            val trackId = reading.trackId.takeIf { it != DistanceMeter.UNTRACKED_ID }
            return Hit(meters, reading.label, centerX, trackId)
        }

        /**
         * Seuil parlé : 15 m droit devant pour les corps et le poteau
         * (palier loin, couloir strict — ordre user du 01/11), portée
         * proche sinon (le corps hors couloir garde 10 m, SPEC v4).
         */
        private fun nearLimit(label: String, person: Boolean, inCorridor: Boolean): Float {
            if (inCorridor && person) return HazardWatcher.PERSON_FAR_METERS
            if (inCorridor && HazardConcepts.isPole(label)) return HazardWatcher.POLE_FAR_METERS
            return if (person) HazardWatcher.PERSON_METERS else HazardWatcher.HAZARD_METERS
        }
    }
}

/**
 * Ordre d'esquive : [phrase] le mot d'évitement (« Droite. »/« Gauche. »,
 * tous les dangers parlent en continu depuis le 03/11 — plus de doux),
 * [meters] la distance du signal qui l'a déclenché (journal, réglages),
 * [urgent] toujours vrai (le mot part en clip `guide_*`, jamais fusionné),
 * [kind] sa sorte, [safetyPhrase] toujours `null` (le clipId casserait la
 * fusion), [level] le niveau VoiceBus v4 (2 la personne, 4 les
 * grillages/murs/barrières, 7 les poteaux et divers) et [antiRepeatKey] sa
 * clé AntiRepeat v4 (mots continus, toujours autorisés).
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
    /** Niveau VoiceBus v4 (voir [HazardWatcher], 2/4/7 dehors). */
    val level: Int = HazardWatcher.LEVEL_PERSON,
    /** Clé AntiRepeat v4 de l'annonce (voir [HazardWatcher]). */
    val antiRepeatKey: String = HazardWatcher.KEY_HUMAN_SOFT,
)

/**
 * Phrases des dangers : le mot d'évitement ([avoidWord], tous les dangers
 * en continu depuis le 03/11) et le formateur « Attention … » ([phrase],
 * FR/EN comme les positions — le guetteur ne le parle plus, gardé pour le
 * corpus des chaînes de clips EN).
 */
object HazardText {
    /** Axe marché : en deçà, l'obstacle est à gauche — on passe à droite. */
    private const val AXIS_MID = 0.5f

    /**
     * « Gauche. » / « Left. » : tous les dangers disent le mot d'évitement
     * en continu — le côté conseillé est l'opposé de l'obstacle (à gauche
     * de l'axe = on passe à droite). Axe pile ou position inconnue = droite
     * par défaut (prior, à valider rue : on serre à droite). Le mot part en
     * clip, voir [HazardWatcher].
     */
    fun avoidWord(
        language: SpeechLanguage,
        centerX: Float? = null,
    ): String = GuidanceWord.fromCap(avoidSide(centerX)).text(language)

    /**
     * Côté d'évitement conseillé : l'opposé de l'obstacle (à gauche de
     * l'axe = on passe à droite). Plein axe ou heure inconnue = droite, la
     * règle de [WidestGap.TIE], sans mémoire : près du centre, le bruit du
     * cadre ne choisit plus le côté. Lue par le mot continu
     * ([avoidWord]) quand le chemin ne conseille rien.
     */
    fun avoidSide(centerX: Float?): SteeringCap =
        if (centerX == null || centerX <= AXIS_MID + WidestGap.TIE / 2f) SteeringCap.Right else SteeringCap.Left

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
 * Parole du [HazardSignal] : tous les dangers disent le mot d'évitement en
 * continu dès la 1re frame (« Droite. »/« Gauche. », clips `guide_*`,
 * départ < 100 ms, répété toutes les [AVOID_REPEAT_MS] tant que présent,
 * immédiat au changement de mot — la position optimale se dit en
 * permanence). Plus de doux depuis le 03/11 (ordre user : les obstacles
 * s'esquivent, ils ne se nomment plus). Niveaux 2/4/7 inchangés.
 *
 * Pur JVM et testé.
 */
class HazardWatcher {
    /** Dernier mot d'évitement parlé, `null` si aucun (porte de répétition). */
    private var lastAvoidWord: String? = null

    /** Instant du dernier mot d'évitement, [NO_INSTANT] si aucun. */
    private var lastAvoidMs: Long = NO_INSTANT

    /** Candidat parlé de la frame : sorte, distance, centre, niveau v4 et mur. */
    private data class Candidate(
        val kind: HazardKind,
        val meters: Float?,
        val centerX: Float?,
        val level: Int,
        val trackId: Int? = null,
        val wall: Boolean = false,
        val side: GuidanceSide? = null,
    )

    /**
     * Alimente la parole avec le [signal] de la frame à [nowMs] : le mot
     * d'évitement en continu, `null` sinon (rien à dire ou mot déjà dit
     * il y a moins de [AVOID_REPEAT_MS]).
     */
    fun onSignal(signal: HazardSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): HazardCue? {
        val candidate = candidateOf(signal) ?: return null
        return onContinuous(candidate, nowMs, language)
    }

    /** Candidat de la frame : le signal retenu, `null` si rien à dire. */
    private fun candidateOf(signal: HazardSignal): Candidate? {
        if (!signal.detected) return null
        val label = signal.label ?: return null
        if (!HazardConcepts.isSpoken(label)) return null
        return Candidate(HazardConcepts.kindOf(label), signal.meters, signal.centerX,
            HazardConcepts.levelOf(label), trackId = signal.trackId, wall = HazardConcepts.isWall(label),
            side = signal.side)
    }

    /**
     * Mot d'évitement continu : dit dès la 1re frame (clip `guide_*`,
     * répété toutes les [AVOID_REPEAT_MS], immédiat au changement de mot).
     * Clé immédiate par famille (personne, poteau, mur, clôture/barrière,
     * divers) : le bus ne freine jamais l'esquive.
     */
    private fun onContinuous(candidate: Candidate, nowMs: Long, language: SpeechLanguage): HazardCue? {
        val word = candidate.side?.let { GuidanceWord.fromSide(it).text(language) }
            ?: HazardText.avoidWord(language, candidate.centerX)
        if (word == lastAvoidWord && nowMs - lastAvoidMs < AVOID_REPEAT_MS) return null
        lastAvoidWord = word
        lastAvoidMs = nowMs
        return HazardCue(word, candidate.meters, urgent = true, kind = candidate.kind, trackId = candidate.trackId,
            level = candidate.level, antiRepeatKey = avoidKey(candidate))
    }

    /** Clé AntiRepeat du mot continu : immédiate, par famille de danger. */
    private fun avoidKey(candidate: Candidate): String = when {
        candidate.kind == HazardKind.PERSON -> KEY_HUMAN_IMMINENT
        candidate.kind == HazardKind.POLE -> KEY_POLE_AVOID
        candidate.wall -> KEY_WALL_AVOID
        candidate.level == LEVEL_FENCE -> KEY_FENCE_AVOID
        else -> KEY_MISC_AVOID
    }

    /** Oublie le mot continu (nouvelle séance de direct). */
    fun reset() {
        lastAvoidWord = null
        lastAvoidMs = NO_INSTANT
    }

    companion object {
        /** Au-delà de cette distance (m), le mobilier ne fait pas parler (SPEC v4 : 6 m). */
        const val HAZARD_METERS = 6f

        /** Au-delà de cette distance (m), une personne hors couloir ne fait pas parler (SPEC v4 : 10 m). */
        const val PERSON_METERS = 10f

        /** Personne à cette distance (m) ou moins : plein axe, le bord du cadre percute comme le centre. */
        const val PERSON_FAST_METERS = 2f

        /** Au-delà de cette distance (m), une personne droit devant ne fait pas parler (palier loin, ordre user du 01/11 : 15 m). */
        const val PERSON_FAR_METERS = 15f

        /** Au-delà de cette distance (m), un poteau droit devant ne fait pas parler (palier loin, ordre user du 01/11 : 15 m). */
        const val POLE_FAR_METERS = 15f

        /**
         * Répétition du mot d'évitement : il reparle toutes les 300 ms tant
         * que le danger est là (miroir du `HEARTBEAT_MS` traversée — le core
         * ne voit pas l'app, la valeur est dupliquée, pas partagée).
         */
        const val AVOID_REPEAT_MS = 300L

        /** Niveau VoiceBus v4 : humains (phrase + cadran + distance, imminent ≤ 2 m). */
        const val LEVEL_PERSON = 3

        /** Niveau VoiceBus v4 : grillages, murs et barrières (< 6 m). */
        const val LEVEL_FENCE = 5

        /** Niveau VoiceBus v4 : poteaux, mobilier vertical et divers (< 6 m). */
        const val LEVEL_POLE = 8

        /** Clé AntiRepeat v4 : personne douce (2 s). */
        const val KEY_HUMAN_SOFT = "human-soft"

        /** Clé AntiRepeat v4 : personne imminente (immédiat, répété à 300 ms). */
        const val KEY_HUMAN_IMMINENT = "human-imminent"

        /** Clé AntiRepeat v4 : mot d'évitement poteau continu (immédiat, répété à 300 ms). */
        const val KEY_POLE_AVOID = "pole-avoid"

        /** Clé AntiRepeat v4 : mot d'évitement mur continu (immédiat, répété à 300 ms). */
        const val KEY_WALL_AVOID = "wall-avoid"

        /** Clé AntiRepeat v4 : mot continu clôture/barrière (immédiat, répété à 300 ms). */
        const val KEY_FENCE_AVOID = "fence-avoid"

        /** Clé AntiRepeat v4 : mot continu divers (immédiat, répété à 300 ms). */
        const val KEY_MISC_AVOID = "misc-avoid"

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
