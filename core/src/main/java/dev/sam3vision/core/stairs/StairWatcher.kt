package dev.sam3vision.core.stairs

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce d'escalier : [phrase] dans la langue de la voix, [direction]
 * son sens, [confidence] et [lineCount] du signal qui l'a déclenché (journal,
 * réglages ; voie labels SAM : confiance 1, 0 ligne — le Canny seul compte).
 *
 * En montée, [lineCount] est le compte **lissé** (médiane de la tendance, voir
 * [StairWatcher]) ; [meters] porte la profondeur quand elle est connue
 * (dernière marche à moins d'un mètre). [level] est le niveau VoiceBus v4
 * (6, escaliers) et [antiRepeatKey] sa clé AntiRepeat v4 (`stairs`, 2 s).
 */
data class StairCue(
    val phrase: String,
    val confidence: Float,
    val lineCount: Int,
    val direction: StairDirection = StairDirection.Unknown,
    val meters: Float? = null,
    /** Niveau VoiceBus v4 : escaliers. */
    val level: Int = StairWatcher.LEVEL,
    /** Clé AntiRepeat v4 : `stairs` (2 s). */
    val antiRepeatKey: String = StairWatcher.KEY_STAIRS,
)

/**
 * Phrases d'escalier, FR/EN comme les positions (`PositionText`) : une info de
 * navigation (« Escalier montant. »), jamais une consigne d'évitement — on ne
 * contourne pas l'escalier qu'on veut prendre.
 */
object StairText {
    /** « Escalier montant, à 11 heures. » (`null` = sans heure, ex. flux Canny). */
    fun phrase(
        language: SpeechLanguage,
        direction: StairDirection = StairDirection.Unknown,
        centerX: Float? = null,
    ): String = PositionText.withHour(base(language, direction), centerX, language)

    /**
     * « Escalier montant à 4 mètres. » / « Stairs up at 4 meters. » — jalon
     * d'approche (miroir `CurbText.distancePhrase`, §24) : la distance
     * entière remplace le cadran, sans heure (miroir §5 positions).
     */
    fun distancePhrase(
        language: SpeechLanguage,
        direction: StairDirection = StairDirection.Unknown,
        tierMeters: Float,
    ): String {
        val base = base(language, direction)
        val spoken = DistanceText.spokenShort(tierMeters, language)
        return when (language) {
            SpeechLanguage.Fr -> "$base à $spoken."
            SpeechLanguage.En -> "$base at $spoken."
        }
    }

    /** Base nominale (« Escalier montant »), sans heure ni distance. */
    private fun base(language: SpeechLanguage, direction: StairDirection): String = when (language) {
        SpeechLanguage.Fr -> when (direction) {
            StairDirection.Up -> "Escalier montant"
            StairDirection.Down -> "Escalier descendant"
            StairDirection.Unknown -> "Escalier devant"
        }
        SpeechLanguage.En -> when (direction) {
            StairDirection.Up -> "Stairs up"
            StairDirection.Down -> "Stairs down"
            StairDirection.Unknown -> "Stairs ahead"
        }
    }

    /**
     * « Plus que N marches. » — le compte à rebours de la montée, depuis le
     * compte lissé ([StairWatcher.smoothedLineCount]) ; N ≤ 1 retombe sur
     * [lastStep] (on ne dit jamais « Plus que 1 marches. »).
     */
    fun countdown(
        remaining: Int,
        language: SpeechLanguage,
        direction: StairDirection = StairDirection.Unknown,
    ): String {
        if (remaining <= StairWatcher.LAST_STEP_LINES) return lastStep(language, direction)
        return when (language) {
            SpeechLanguage.Fr -> "Plus que $remaining marches."
            SpeechLanguage.En -> "$remaining steps left."
        }
    }

    /**
     * « 5 marches. » — le comptage oral des marches vues, dit quand
     * l'escalier est reconnu (voix du compteur, [StairCountVoice]), à ne pas
     * confondre avec [countdown] (le « Plus que N » de la montée). Singulier
     * gardé (« 1 marche. », jamais « 1 marches. »).
     */
    fun stepCount(count: Int, language: SpeechLanguage): String {
        if (count == 1) return when (language) {
            SpeechLanguage.Fr -> "1 marche."
            SpeechLanguage.En -> "1 step."
        }
        return when (language) {
            SpeechLanguage.Fr -> "$count marches."
            SpeechLanguage.En -> "$count steps."
        }
    }

    /**
     * « Dernière marche. » — plus qu'une marche (compteur lissé ≤ 1) ou le
     * palier à moins d'un mètre. En descente, le palier est nommé (« Dernière
     * marche, palier. ») : on arrive quelque part, pas dans le vide.
     */
    fun lastStep(
        language: SpeechLanguage,
        direction: StairDirection = StairDirection.Unknown,
    ): String = when (language) {
        SpeechLanguage.Fr ->
            if (direction == StairDirection.Down) "Dernière marche, palier." else "Dernière marche."
        SpeechLanguage.En ->
            if (direction == StairDirection.Down) "Last step, landing." else "Last step."
    }
}

/**
 * Lissage temporel du [StairSignal] (SPEC v4 : escaliers niveau 6, jalons
 * 4/2/1 m, nombre de marches dit, silence 2 s) : un positif isolé ne parle
 * pas, il faut [confirmFrames] positifs consécutifs, puis silence pendant
 * [cooldownMs].
 *
 * Mode montée : une fois l'escalier annoncé, le compteur Canny lissé pilote un
 * compte à rebours (« Plus que N marches. ») puis la dernière marche, au
 * cooldown court [climbCooldownMs] (2 s) au lieu du standard — pendant qu'on
 * monte, le décor change à chaque pas. Le lissage est la médiane des
 * [trendFrames] derniers comptes (2-3 frames, pas le brut : un pic isolé ne
 * fait ni décrémenter ni conclure) ; la tendance traverse le seuil du
 * détecteur (un négatif sous [StairsConfig.minLines] mais compté nourrit
 * encore la descente vers la dernière marche).
 *
 * Compte à rebours d'approche (miroir §24 bordure) : quand la profondeur est
 * connue, l'escalier traversé se redit à 4 m, 2 m puis 1 m ([COUNTDOWN_METERS]),
 * une fois par palier et par approche, sans cooldown entre paliers (l'approche
 * à pied les enchaîne). Hystérésis [REARM_MARGIN_METERS] (recul au-delà = le
 * palier reparle), palier survolé = sauté sans rattrapage, signal perdu =
 * paliers ré-armés. Un palier ne parle que **traversé** (vu d'au-delà
 * d'abord) : l'entrée directe parle standard sans distance (jamais un jalon),
 * puis la montée suit son cours.
 *
 * Conventions :
 * - `lineCount = 0` sur un positif = compteur indisponible (voie labels SAM,
 *   qui nomme sans compter) : annonce standard, jamais de compte à rebours —
 *   0 n'est pas « zéro marche », c'est « non compté » ;
 * - dernière marche seulement **après** l'annonce standard (jamais en
 *   première rencontre) : compte lissé ≤ [LAST_STEP_LINES] après une montée
 *   vue (pic lissé > 1, sinon un compte bas vu du haut ne conclut pas) **ou**
 *   profondeur [LAST_STEP_METERS] quand le compteur est indisponible (la
 *   proximité ne préempte jamais un compte qui montre des marches restantes) ;
 * - la séance expire après [SESSION_EXPIRY_MS] sans annonce : une rencontre plus
 *   tardive se ré-annonce en standard, pas en « Plus que N » d'une montée
 *   d'avant.
 *
 * Compteur recalibré pour la luminance téléphone 256×103 (voir
 * `StairsConfig`) ; validation live sur captures réelles recommandée.
 *
 * Pur JVM et testé : le branchement au direct (`LiveViewModel.onFrame`)
 * n'aura qu'à appeler [onSignal] puis la synthèse existante.
 */
class StairWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val climbCooldownMs: Long = CLIMB_COOLDOWN_MS,
    trendFrames: Int = TREND_FRAMES,
) {
    /** Fenêtre de tendance, bornée (au moins [TREND_MIN], sinon pas de tendance). */
    private val window: Int = trendFrames.coerceIn(TREND_MIN, MAX_TREND_FRAMES)

    /** Positifs consécutifs en cours (un négatif hors montée remet à zéro). */
    private var hits: Int = 0

    /** Instant de la dernière annonce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /** Derniers comptes Canny (que les positifs : 0 = non compté, ignoré). */
    private val trend: ArrayDeque<Int> = ArrayDeque()

    /** Compte lissé de la dernière annonce de la montée en cours, `null` = pas en montée. */
    private var climbCount: Int? = null

    /** Dernière marche déjà dite pour cette approche (dite une fois, pas en boucle). */
    private var lastStepSaid: Boolean = false

    /** Montée ouverte : l'annonce standard a été dite, le compte peut décroître. */
    private var opened: Boolean = false

    /** Plus haut compte lissé vu depuis l'ouverture (un pic isolé n'arme rien). */
    private var peakSmoothed: Int? = null

    /** Frames consécutives sous le palier visé (autre chose remet à zéro). */
    private var tierHits: Int = 0

    /** Palier visé en cours de confirmation, `null` si aucun. */
    private var tierTarget: Float? = null

    /** Paliers dits (ou survolés) de l'approche en cours. */
    private val spokenTiers = mutableSetOf<Float>()

    /** Paliers armés : vus d'au-delà (un palier ne parle que traversé). */
    private val armedTiers = mutableSetOf<Float>()

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /** Compte lissé courant (médiane de la tendance), `null` = compteur indisponible. */
    val smoothedLineCount: Int?
        get() = median()

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint hors cooldown, `null` sinon.
     * [direction] est le sens annoncé (voie labels SAM ; le Canny seul ne le
     * voit pas et garde [StairDirection.Unknown]). [centerX] est le centre
     * normalisé (l'heure du cadran, `null` = sans heure). [meters] est la
     * profondeur du palier quand elle est connue (`null` = inconnue, le
     * compte à rebours d'approche attend une distance connue).
     */
    fun onSignal(
        signal: StairSignal,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        direction: StairDirection = StairDirection.Unknown,
        centerX: Float? = null,
        meters: Float? = null,
    ): StairCue? {
        expireClimb(nowMs)
        if (signal.detected) hits++
        if (signal.lineCount > 0) {
            trend.addLast(signal.lineCount)
            while (trend.size > window) trend.removeFirst()
        }
        val smoothed = median()
        if (opened && smoothed != null && smoothed > 0) {
            peakSmoothed = maxOf(peakSmoothed ?: 0, smoothed)
        }
        val nearDepth = meters != null && meters.isFinite() && meters < LAST_STEP_METERS
        val announced = climbCount
        // Dernière marche verrouillée : jamais en première rencontre (l'annonce
        // standard ouvre toujours), jamais sur un compte bas sans montée vue
        // (vu du haut ou occulté n'est pas arrivé), profondeur seule quand le
        // compteur est indisponible (proche n'est pas fini quand il reste des marches).
        val countLast = opened && !lastStepSaid && smoothed != null &&
            smoothed <= LAST_STEP_LINES && (peakSmoothed ?: 0) > LAST_STEP_LINES
        val depthLast = opened && !lastStepSaid && nearDepth && smoothed == null
        val lastStep = countLast || depthLast
        val countdown = !lastStep && smoothed != null && announced != null &&
            smoothed < announced && smoothed > LAST_STEP_LINES
        if (lastStep || countdown) {
            return climbCue(signal, lastStep, depthLast, smoothed, announced, nowMs, language, direction, meters)
        }
        if (!signal.detected) {
            hits = 0
            resetTiers()
            return null
        }
        onCountdown(signal, nowMs, language, direction, meters)?.let { return it }
        if (hits < confirmFrames) return null
        if (tiersOwnVoice(meters)) return null
        if (!cooldownOk(nowMs, cooldownMs)) return null
        markApproach(nowMs)
        return StairCue(
            StairText.phrase(language, direction, centerX), signal.confidence, signal.lineCount, direction, meters,
            level = LEVEL, antiRepeatKey = KEY_STAIRS,
        )
    }

    /**
     * Annonce de montée (dernière marche ou compte à rebours) : `null` = pas
     * encore (tendance ou porte standard non confirmée, cooldown montée).
     * [announced] est le compte déjà annoncé (`null` = sans compteur, voie
     * labels : seule la profondeur confirmée peut alors conclure).
     */
    private fun climbCue(
        signal: StairSignal,
        lastStep: Boolean,
        depthLast: Boolean,
        smoothed: Int?,
        announced: Int?,
        nowMs: Long,
        language: SpeechLanguage,
        direction: StairDirection,
        meters: Float?,
    ): StairCue? {
        // En montée comptée, la tendance confirmée (2-3 frames) vaut confirmation ;
        // sans compteur, la profondeur exige la porte standard après l'annonce.
        val trendOk = announced != null && trend.size >= TREND_MIN
        val depthOk = depthLast && signal.detected && hits >= confirmFrames
        if (!trendOk && !depthOk) {
            if (!signal.detected) hits = 0
            return null
        }
        if (!cooldownOk(nowMs, climbCooldownMs)) return null
        lastCueAtMs = nowMs
        hits = 0
        if (lastStep) {
            lastStepSaid = true
            opened = false
            peakSmoothed = null
            climbCount = null
            trend.clear()
            return StairCue(
                StairText.lastStep(language, direction), signal.confidence,
                smoothed ?: signal.lineCount, direction, meters,
                level = LEVEL, antiRepeatKey = KEY_STAIRS,
            )
        }
        val remaining = smoothed ?: return null
        climbCount = remaining
        return StairCue(
            StairText.countdown(remaining, language, direction), signal.confidence,
            remaining, direction, meters,
            level = LEVEL, antiRepeatKey = KEY_STAIRS,
        )
    }

    /**
     * Compte à rebours d'approche (miroir §24 bordure) : le palier visé est
     * le plus proche restant à dire sous lequel on est passé ; les paliers
     * survolés sont verrouillés muets (périmés), le recul les ré-arme.
     * Différence bordure : un palier ne parle que **traversé** (vu d'au-delà
     * d'abord) — l'entrée directe parle standard sans distance, jamais un
     * jalon (verrou `StairLastStepGateTest`).
     */
    private fun onCountdown(
        signal: StairSignal,
        nowMs: Long,
        language: SpeechLanguage,
        direction: StairDirection,
        meters: Float?,
    ): StairCue? {
        if (meters == null || !meters.isFinite()) return null
        COUNTDOWN_METERS.filter { meters > it }.forEach(armedTiers::add)
        spokenTiers.removeAll { meters > it + REARM_MARGIN_METERS }
        val target = COUNTDOWN_METERS
            .filter { it >= meters && it !in spokenTiers && it in armedTiers }
            .minOrNull()
        if (target == null) {
            tierHits = 0
            tierTarget = null
            return null
        }
        spokenTiers.addAll(COUNTDOWN_METERS.filter { it > target })
        if (target != tierTarget) {
            tierTarget = target
            tierHits = 1
            return null
        }
        tierHits++
        if (tierHits < confirmFrames) return null
        tierTarget = null
        tierHits = 0
        spokenTiers.add(target)
        markApproach(nowMs)
        return StairCue(
            StairText.distancePhrase(language, direction, target),
            signal.confidence, signal.lineCount, direction, meters,
            level = LEVEL, antiRepeatKey = KEY_STAIRS,
        )
    }

    /**
     * L'annonce d'approche (détection ou jalon) ouvre la montée : le compte
     * à rebours et la dernière marche peuvent suivre.
     */
    private fun markApproach(nowMs: Long) {
        val smoothed = median()
        lastCueAtMs = nowMs
        hits = 0
        lastStepSaid = false
        opened = true
        climbCount = smoothed
        peakSmoothed = smoothed?.takeIf { it > 0 }
    }

    /** Oublie les paliers (la prochaine approche se redit : traversée exigée). */
    private fun resetTiers() {
        tierHits = 0
        tierTarget = null
        spokenTiers.clear()
        armedTiers.clear()
    }

    /** Oublie le compte, la tendance et l'annonce (nouvelle séance de direct). */
    fun reset() {
        opened = false
        peakSmoothed = null
        hits = 0
        lastCueAtMs = NO_INSTANT
        trend.clear()
        climbCount = null
        lastStepSaid = false
        resetTiers()
    }

    /** Séance expirée ([SESSION_EXPIRY_MS] sans annonce) : la montée d'avant est oubliée. */
    private fun expireClimb(nowMs: Long) {
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs >= SESSION_EXPIRY_MS) {
            trend.clear()
            climbCount = null
            lastStepSaid = false
            opened = false
            peakSmoothed = null
            resetTiers()
        }
    }

    /**
     * Jalons maîtres de la voix : approche ouverte avec profondeur connue.
     */
    private fun tiersOwnVoice(meters: Float?): Boolean = opened && meters != null && meters.isFinite()

    /** Silence [cd] écoulé depuis la dernière annonce (ou aucune annonce encore). */
    private fun cooldownOk(nowMs: Long, cd: Long): Boolean =
        lastCueAtMs == NO_INSTANT || nowMs - lastCueAtMs >= cd

    /** Médiane de la tendance (borne haute à taille paire : le compte prudent). */
    private fun median(): Int? {
        if (trend.isEmpty()) return null
        val sorted = trend.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 3

        /** Silence entre deux annonces standard (SPEC v4 : 2 s, miroir de la table AntiRepeat). */
        const val DEFAULT_COOLDOWN_MS = 2_000L

        /** Silence entre deux annonces de montée (SPEC v4 : 2 s — le décor change à chaque pas). */
        const val CLIMB_COOLDOWN_MS = 2_000L

        /**
         * Mémoire de séance : 10 s sans annonce avant d'oublier la montée.
         * Strictement plus long que les cooldowns de parole (2 s) — sinon
         * l'expiration tuerait la montée au moment précis où le compte à
         * rebours aurait le droit de parler.
         */
        const val SESSION_EXPIRY_MS = 10_000L

        /** Fenêtre du lissage : 3 derniers comptes (tendance 2-3 frames, pas le brut). */
        const val TREND_FRAMES = 3

        /** Échantillons minimaux pour une tendance (en deçà : pas de compte à rebours). */
        const val TREND_MIN = 2

        /** Fenêtre maximale (un lissage trop long retarderait la dernière marche). */
        const val MAX_TREND_FRAMES = 8

        /** Compte lissé de la dernière marche (1 : on y est ; 0 = non compté, jamais). */
        const val LAST_STEP_LINES = 1

        /** Profondeur du palier de la dernière marche (mètres). */
        const val LAST_STEP_METERS = 1f

        /** Paliers du compte à rebours d'approche (m), du plus loin au plus proche (SPEC v4 : 4/2/1). */
        val COUNTDOWN_METERS = listOf(4f, 2f, 1f)

        /**
         * Hystérésis : un palier dit se ré-arme quand on recule au-delà de
         * T + marge (prior, à valider rue comme le §24 bordure).
         */
        const val REARM_MARGIN_METERS = 0.2f

        /** Niveau VoiceBus v4 : escaliers (jalons 4/2/1 + nombre de marches dit). */
        const val LEVEL = 6

        /** Clé AntiRepeat v4 : escaliers (2 s). */
        const val KEY_STAIRS = "stairs"

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
