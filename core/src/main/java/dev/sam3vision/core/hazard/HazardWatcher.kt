package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Signal danger d'une frame : [detected] quand une personne ou du mobilier
 * suivi est dans le couloir marché à [HazardWatcher.HAZARD_METERS] ou moins
 * ([HazardWatcher.PERSON_METERS] pour une personne, 15 m droit devant pour
 * la personne et le poteau — [HazardWatcher.PERSON_FAR_METERS] et
 * [HazardWatcher.POLE_FAR_METERS], ordre user du 01/11), [meters] sa
 * distance, [label] son libellé SAM brut (la phrase y lit le nom parlé),
 * [centerX] son centre normalisé (l'heure du cadran, `null` = inconnue).
 * La personne prime : présente, elle est retenue même si une barrière est
 * plus proche ; sinon le plus proche gagne. SPEC v4 : niveaux VoiceBus
 * (2 la personne, 4 grillages/murs/barrières, 7 poteaux et divers) ; la
 * borne et le portail ne sont plus reconnus. Sans repli : le mot continu
 * ne cède jamais la parole tant que la personne ou le poteau est là.
 */
data class HazardSignal(
    val detected: Boolean,
    val meters: Float?,
    val label: String?,
    val centerX: Float? = null,
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
         * même au bord du cadre) — mesuré sous le seuil (15 m droit devant
         * pour la personne et le poteau, portée proche sinon). La personne
         * prime sur tout non-personne (P3 avant P4) ; à sorte égale le plus
         * proche gagne ; boîte illisible ou dimensions nulles = ignoré.
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
            val person = HazardConcepts.isPerson(reading.label)
            // Personne imminente : plein axe — à ≤ 2 m, le bord du cadre percute autant que le centre.
            val nearPerson = person && meters <= HazardWatcher.PERSON_FAST_METERS
            if (!nearPerson && !inCorridor) return null
            if (meters > nearLimit(reading.label, person, inCorridor)) return null
            val trackId = reading.trackId.takeIf { it != DistanceMeter.UNTRACKED_ID }
            return Hit(meters, reading.label, centerX, trackId)
        }

        /**
         * Seuil parlé : 15 m droit devant pour la personne et le poteau
         * (palier loin, couloir strict — ordre user du 01/11), portée
         * proche sinon (la personne hors couloir garde 10 m, SPEC v4).
         */
        private fun nearLimit(label: String, person: Boolean, inCorridor: Boolean): Float {
            if (inCorridor && person) return HazardWatcher.PERSON_FAR_METERS
            if (inCorridor && HazardConcepts.isPole(label)) return HazardWatcher.POLE_FAR_METERS
            return if (person) HazardWatcher.PERSON_METERS else HazardWatcher.HAZARD_METERS
        }
    }
}

/**
 * Ordre d'annonce « attention … » : [phrase] dans la langue de la voix,
 * [meters] la distance du signal qui l'a déclenché (journal, réglages),
 * [urgent] pour la personne et le poteau (leur mot d'évitement,
 * « Droite. »/« Gauche. », part en clip `guide_*`, jamais fusionné),
 * [kind] sa sorte (l'arbitre voix y lit le rang : personne P3, barrière
 * P4, poteau et divers avec le reste), [safetyPhrase] l'alerte immédiate
 * lue par l'arbitre (`null` pour le mot continu : il part en clip, la
 * fusion casserait le clipId — la barrière reste douce), [level] le niveau
 * VoiceBus v4 (2 la personne, 4 les grillages/murs/barrières, 7 les
 * poteaux et divers) et [antiRepeatKey] sa clé AntiRepeat v4
 * (`human-imminent`, `pole-avoid`, `fence`, `pole`).
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
 * Phrases « Attention … », FR/EN comme les positions (`PositionText`) :
 * « Attention à la barrière, à 2 heures. » / « Attention, barrier, at 2
 * o'clock. » — une info de description, jamais une consigne (l'ordre user :
 * iSee décrit, en douceur, au cadran des aveugles) — sauf la personne et
 * le poteau, qui disent le mot d'évitement en continu (ordre user du
 * 01/11, voir [avoidWord]).
 */
object HazardText {
    /** Axe marché : en deçà, l'obstacle est à gauche — on passe à droite. */
    private const val AXIS_MID = 0.5f

    /**
     * « Gauche. » / « Left. » : la personne et le poteau disent le mot
     * d'évitement en continu — le côté conseillé est l'opposé de
     * l'obstacle (à gauche de l'axe = on passe à droite). Axe pile ou
     * heure inconnue = droite par défaut (prior, à valider rue : on serre
     * à droite). Personne et poteau seuls (les autres dangers restent
     * descriptifs, voir [phrase]) ; le mot part en clip, voir
     * [HazardWatcher].
     */
    fun avoidWord(
        language: SpeechLanguage,
        centerX: Float? = null,
    ): String = GuidanceWord.fromCap(avoidSide(centerX)).text(language)

    /**
     * Côté d'évitement conseillé : l'opposé de l'obstacle (à gauche de
     * l'axe = on passe à droite). Axe pile ou heure inconnue = droite par
     * défaut (prior, à valider rue : on serre à droite). Seule source du
     * côté, lue par le mot continu ([avoidWord]).
     */
    fun avoidSide(centerX: Float?): SteeringCap =
        if (centerX == null || centerX <= AXIS_MID) SteeringCap.Right else SteeringCap.Left

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
 * Parole du [HazardSignal] (SPEC v4 + ordre continu du 01/11) : la
 * personne et le poteau disent le mot d'évitement en continu dès la 1re
 * frame (« Droite. »/« Gauche. », clips `guide_*`, départ < 100 ms,
 * répété toutes les [AVOID_REPEAT_MS] tant que présent, immédiat au
 * changement de mot — la position optimale se dit en permanence) ; le
 * reste parle en doux confirmé ([confirmFrames] positifs consécutifs,
 * puis silence par groupe : grillages/murs/barrières 4 s, divers 2 s —
 * un groupe ne fait plus taire les autres). Le mot continu ne consomme
 * pas les silences doux et ne passe pas par la voix de sécurité.
 *
 * Pur JVM et testé.
 */
class HazardWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    /** Doux consécutifs en cours (un mot continu remet à zéro). */
    private var otherHits: Int = 0

    /** Instant de la dernière annonce grillage/mur/barrière, [NO_INSTANT] si aucune. */
    private var lastFenceMs: Long = NO_INSTANT

    /** Instant de la dernière annonce divers, [NO_INSTANT] si aucune. */
    private var lastDiversMs: Long = NO_INSTANT

    /** Dernier mot d'évitement parlé, `null` si aucun (porte de répétition). */
    private var lastAvoidWord: String? = null

    /** Instant du dernier mot d'évitement, [NO_INSTANT] si aucun. */
    private var lastAvoidMs: Long = NO_INSTANT

    /** Dernière annonce douce, `null` si aucune (le mot continu ne compte pas). */
    val lastAlertMs: Long?
        get() = maxOf(lastFenceMs, lastDiversMs).takeIf { it != NO_INSTANT }

    /** Candidat parlé de la frame : sorte, nom, distance, centre, niveau v4 et continuité. */
    private data class Candidate(
        val kind: HazardKind,
        val name: HazardConcepts.HazardName,
        val meters: Float?,
        val centerX: Float?,
        val level: Int,
        val continuous: Boolean,
        val trackId: Int? = null,
    )

    /**
     * Alimente la parole avec le [signal] de la frame à [nowMs] : le mot
     * d'évitement pour la personne et le poteau, la douce confirmée pour
     * le reste, `null` sinon.
     */
    fun onSignal(signal: HazardSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): HazardCue? {
        val candidate = candidateOf(signal) ?: run { otherHits = 0; return null }
        return if (candidate.continuous) onContinuous(candidate, nowMs, language)
        else onOther(candidate, nowMs, language)
    }

    /**
     * Candidat de la frame : le signal retenu, `null` si rien à dire. La
     * personne et le poteau parlent en continu ; le reste en doux confirmé.
     */
    private fun candidateOf(signal: HazardSignal): Candidate? {
        if (!signal.detected) return null
        val label = signal.label ?: return null
        if (!HazardConcepts.isSpoken(label)) return null
        val name = HazardConcepts.nameOf(label) ?: return null
        val continuous = HazardConcepts.isPerson(label) || HazardConcepts.isPole(label)
        return Candidate(HazardConcepts.kindOf(label), name, signal.meters, signal.centerX,
            HazardConcepts.levelOf(label), continuous, trackId = signal.trackId)
    }

    /**
     * Mot d'évitement continu : dit dès la 1re frame (clip `guide_*`,
     * répété toutes les [AVOID_REPEAT_MS], immédiat au changement de mot).
     */
    private fun onContinuous(candidate: Candidate, nowMs: Long, language: SpeechLanguage): HazardCue? {
        otherHits = 0
        val word = HazardText.avoidWord(language, candidate.centerX)
        if (word == lastAvoidWord && nowMs - lastAvoidMs < AVOID_REPEAT_MS) return null
        lastAvoidWord = word
        lastAvoidMs = nowMs
        val pole = candidate.kind == HazardKind.POLE
        return HazardCue(word, candidate.meters, urgent = true, kind = candidate.kind, trackId = candidate.trackId,
            level = candidate.level, antiRepeatKey = if (pole) KEY_POLE_AVOID else KEY_HUMAN_IMMINENT)
    }

    /**
     * Hors personne et poteau : doux confirmé, silence du groupe
     * (grillages/murs/barrières [FENCE_COOLDOWN_MS], divers [cooldownMs]).
     */
    private fun onOther(candidate: Candidate, nowMs: Long, language: SpeechLanguage): HazardCue? {
        otherHits++
        if (otherHits < confirmFrames) return null
        val fence = candidate.level == LEVEL_FENCE
        val gap = if (fence) FENCE_COOLDOWN_MS else cooldownMs
        val last = if (fence) lastFenceMs else lastDiversMs
        if (last != NO_INSTANT && nowMs - last < gap) return null
        if (fence) lastFenceMs = nowMs else lastDiversMs = nowMs
        otherHits = 0
        return HazardCue(
            HazardText.phrase(candidate.name, language, candidate.centerX),
            candidate.meters, kind = candidate.kind, trackId = candidate.trackId,
            level = candidate.level,
            antiRepeatKey = if (fence) KEY_FENCE else KEY_POLE,
        )
    }

    /** Oublie les comptes, les silences doux et le mot continu (nouvelle séance de direct). */
    fun reset() {
        otherHits = 0
        lastFenceMs = NO_INSTANT
        lastDiversMs = NO_INSTANT
        lastAvoidWord = null
        lastAvoidMs = NO_INSTANT
    }

    companion object {
        /** Doux consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces douces divers (SPEC v4 : 2 s ; le mot continu l'ignore). */
        const val DEFAULT_COOLDOWN_MS = 2_000L

        /** Silence entre deux annonces grillages/murs/barrières (SPEC v4 : 4 s). */
        const val FENCE_COOLDOWN_MS = 4_000L

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
         * que la personne ou le poteau est là (miroir du `HEARTBEAT_MS`
         * traversée — le core ne voit pas l'app, la valeur est dupliquée,
         * pas partagée).
         */
        const val AVOID_REPEAT_MS = 300L

        /** Niveau VoiceBus v4 : humains (phrase + cadran + distance, imminent ≤ 2 m). */
        const val LEVEL_PERSON = 2

        /** Niveau VoiceBus v4 : grillages, murs et barrières (< 6 m). */
        const val LEVEL_FENCE = 4

        /** Niveau VoiceBus v4 : poteaux, mobilier vertical et divers (< 6 m). */
        const val LEVEL_POLE = 7

        /** Clé AntiRepeat v4 : personne douce (2 s). */
        const val KEY_HUMAN_SOFT = "human-soft"

        /** Clé AntiRepeat v4 : personne imminente (immédiat, répété à 300 ms). */
        const val KEY_HUMAN_IMMINENT = "human-imminent"

        /** Clé AntiRepeat v4 : mot d'évitement poteau continu (immédiat, répété à 300 ms). */
        const val KEY_POLE_AVOID = "pole-avoid"

        /** Clé AntiRepeat v4 : grillages, murs et barrières (4 s). */
        const val KEY_FENCE = "fence"

        /** Clé AntiRepeat v4 : poteaux, mobilier vertical et divers (2 s). */
        const val KEY_POLE = "pole"

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
