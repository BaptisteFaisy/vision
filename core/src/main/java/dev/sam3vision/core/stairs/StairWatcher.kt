package dev.sam3vision.core.stairs

import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce d'escalier : [phrase] dans la langue de la voix, [direction]
 * son sens, [confidence] et [lineCount] du signal qui l'a déclenché (journal,
 * réglages ; voie labels SAM : confiance 1, 0 ligne — le Canny seul compte).
 *
 * En montée, [lineCount] est le compte **lissé** (médiane de la tendance, voir
 * [StairWatcher]) ; [meters] porte la profondeur quand elle est connue
 * (dernière marche à moins d'un mètre).
 */
data class StairCue(
    val phrase: String,
    val confidence: Float,
    val lineCount: Int,
    val direction: StairDirection = StairDirection.Unknown,
    val meters: Float? = null,
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
    ): String {
        val base = when (language) {
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
        return PositionText.withHour(base, centerX, language)
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
 * Lissage temporel du [StairSignal] : un positif isolé ne parle pas, il faut
 * [confirmFrames] positifs consécutifs, puis silence pendant [cooldownMs]
 * (miroir du `VOICE_COOLDOWN` MIT, 3 s, monté à 10 s : un escalier ne bouge
 * pas, inutile de le répéter à chaque pas).
 *
 * Mode montée : une fois l'escalier annoncé, le compteur Canny lissé pilote un
 * compte à rebours (« Plus que N marches. ») puis la dernière marche, au
 * cooldown court [climbCooldownMs] (2,5 s) au lieu de 10 s — pendant qu'on
 * monte, le décor change à chaque pas. Le lissage est la médiane des
 * [trendFrames] derniers comptes (2-3 frames, pas le brut : un pic isolé ne
 * fait ni décrémenter ni conclure) ; la tendance traverse le seuil du
 * détecteur (un négatif sous [StairsConfig.minLines] mais compté nourrit
 * encore la descente vers la dernière marche).
 *
 * Conventions :
 * - `lineCount = 0` sur un positif = compteur indisponible (voie labels SAM,
 *   qui nomme sans compter) : annonce standard, jamais de compte à rebours —
 *   0 n'est pas « zéro marche », c'est « non compté » ;
 * - dernière marche si compte lissé ≤ [LAST_STEP_LINES] **ou** profondeur
 *   [LAST_STEP_METERS] (mètres du palier, branche profondeur) ;
 * - la séance expire après [cooldownMs] sans annonce : une rencontre plus
 *   tardive se ré-annonce en standard, pas en « Plus que N » d'une montée
 *   d'avant.
 *
 * Bloqueur connu : seuils Canny calibrés webcam 640×480, à recalibrer sur
 * captures du téléphone (voir `StairDetector`) — le compteur, donc le compte
 * à rebours, n'est pas fiable avant ce calibrage.
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
     * profondeur du palier quand elle est connue (`null` = inconnue).
     */
    fun onSignal(
        signal: StairSignal,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
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
        val nearDepth = meters != null && meters.isFinite() && meters < LAST_STEP_METERS
        val announced = climbCount
        val lastStep = !lastStepSaid && (nearDepth || (smoothed != null && smoothed <= LAST_STEP_LINES))
        val countdown = !lastStep && smoothed != null && announced != null &&
            smoothed < announced && smoothed > LAST_STEP_LINES
        if (lastStep || countdown) {
            return climbCue(signal, lastStep, smoothed, announced, nowMs, language, direction, meters)
        }
        if (!signal.detected) {
            hits = 0
            return null
        }
        if (hits < confirmFrames) return null
        if (!cooldownOk(nowMs, cooldownMs)) return null
        lastCueAtMs = nowMs
        hits = 0
        lastStepSaid = false
        climbCount = smoothed
        return StairCue(
            StairText.phrase(language, direction, centerX), signal.confidence, signal.lineCount, direction, meters,
        )
    }

    /**
     * Annonce de montée (dernière marche ou compte à rebours) : `null` = pas
     * encore (tendance ou porte standard non confirmée, cooldown montée).
     * [announced] est le compte déjà annoncé (`null` = première annonce).
     */
    private fun climbCue(
        signal: StairSignal,
        lastStep: Boolean,
        smoothed: Int?,
        announced: Int?,
        nowMs: Long,
        language: SpeechLanguage,
        direction: StairDirection,
        meters: Float?,
    ): StairCue? {
        // En montée, la tendance confirmée (2-3 frames) vaut confirmation ;
        // hors montée, la porte standard (positifs consécutifs) s'applique.
        val trendOk = announced != null && trend.size >= TREND_MIN
        val freshOk = announced == null && signal.detected && hits >= confirmFrames
        if (!trendOk && !freshOk) {
            if (!signal.detected) hits = 0
            return null
        }
        if (!cooldownOk(nowMs, climbCooldownMs)) return null
        lastCueAtMs = nowMs
        hits = 0
        if (lastStep) {
            lastStepSaid = true
            climbCount = null
            trend.clear()
            return StairCue(
                StairText.lastStep(language, direction), signal.confidence,
                smoothed ?: signal.lineCount, direction, meters,
            )
        }
        val remaining = smoothed ?: return null
        climbCount = remaining
        return StairCue(
            StairText.countdown(remaining, language, direction), signal.confidence,
            remaining, direction, meters,
        )
    }

    /** Oublie le compte, la tendance et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        lastCueAtMs = NO_INSTANT
        trend.clear()
        climbCount = null
        lastStepSaid = false
    }

    /** Séance expirée ([cooldownMs] sans annonce) : la montée d'avant est oubliée. */
    private fun expireClimb(nowMs: Long) {
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs >= cooldownMs) {
            trend.clear()
            climbCount = null
            lastStepSaid = false
        }
    }

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

        /** Silence entre deux annonces standard (l'escalier ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Silence entre deux annonces de montée (le décor change à chaque pas). */
        const val CLIMB_COOLDOWN_MS = 2_500L

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

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
