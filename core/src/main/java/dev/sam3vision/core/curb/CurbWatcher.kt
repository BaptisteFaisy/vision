package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Palier de proximité d'un bord : [NONE] rien en vue, [FAR] pré-alerte douce
 * (≤ [CurbWatcher.CURB_METERS]), [NEAR] marche imminente, parole urgente
 * (distance lissée ≤ [CurbWatcher.STEP_NEAR_METERS]).
 */
enum class CurbTier { NONE, FAR, NEAR }

/**
 * Sens du bord : [Up] montée (lever le pied), [Down] descente.
 */
enum class CurbEdge { Up, Down }

/**
 * Signal trottoir d'une frame : [detected] quand un bord est dans le couloir
 * marché à [CurbWatcher.CURB_METERS] ou moins, [meters] sa distance lissée
 * (la plus proche en cas de doublons), [centerX] son centre normalisé (l'heure
 * du cadran, `null` = inconnue), [edge] le sens du bord (`null` = construction
 * manuelle, lue comme une montée), [flatPass] quand un plan trottoir continu
 * (bateau : pas de marche) remplit le couloir sans bord proche.
 *
 * [tier] dérive toujours de [detected]/[meters] : pas de signal incohérent.
 */
data class CurbSignal(
    val detected: Boolean,
    val meters: Float?,
    val centerX: Float? = null,
    val edge: CurbEdge? = null,
    val flatPass: Boolean = false,
) {
    /** Palier instantané : imminent sous [CurbWatcher.STEP_NEAR_METERS]. */
    val tier: CurbTier
        get() = when {
            !detected -> CurbTier.NONE
            meters != null && meters <= CurbWatcher.STEP_NEAR_METERS -> CurbTier.NEAR
            else -> CurbTier.FAR
        }

    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): CurbSignal = CurbSignal(detected = false, meters = null)

        /**
         * Signal des lectures affinées ([meters] sténopé ou profondeur, déjà
         * lissée) : un label [CurbConcepts] dans le couloir
         * ([PositionText.SIDE_EDGE]), bas de boîte en bande basse et distance
         * sous le seuil. Le plus proche gagne ; sans bord mais avec un plan
         * continu (bateau), [flatPass]. Boîte illisible ou dimensions nulles =
         * ignoré, pas d'exception.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): CurbSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var best: Triple<Float, Float, CurbEdge>? = null
            var flat: Pair<Float, Float>? = null
            for (reading in readings) {
                val hit = nearCurb(reading, frameW, frameH)
                if (hit != null) {
                    if (best == null || hit.first < best.first) best = hit
                    continue
                }
                val pass = flatPass(reading, frameW, frameH) ?: continue
                if (flat == null || pass.first < flat.first) flat = pass
            }
            if (best != null) {
                return CurbSignal(detected = true, meters = best.first, centerX = best.second, edge = best.third)
            }
            if (flat != null) {
                return CurbSignal(detected = false, meters = flat.first, centerX = flat.second, flatPass = true)
            }
            return none()
        }

        /** Distance + centre + sens si [reading] est un bord proche, `null` sinon. */
        private fun nearCurb(reading: DistanceReading, frameW: Int, frameH: Int): Triple<Float, Float, CurbEdge>? {
            if (!CurbConcepts.isCurb(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            if (meters > CurbWatcher.CURB_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            if (box[3] / frameH < CurbWatcher.LOWER_BAND_FRACTION) return null
            val edge = if (CurbConcepts.isGround(reading.label)) edgeKind(box, frameH) ?: return null else CurbEdge.Up
            return Triple(meters, centerX, edge)
        }

        /**
         * Plan (`sidewalk`…) : bord proche ou sol sous les pieds ? Montée = le
         * plan commence au-dessus des pieds (bas sous [CurbWatcher.PLANE_BOTTOM_MAX]) ;
         * descente = plan collé aux pieds mais borné en haut (haut au-dessus de
         * [CurbWatcher.PLANE_TOP_MIN]) ; sinon c'est le sol sur lequel on marche
         * (`null`). Seuils : priors à valider sur captures téléphone.
         */
        private fun edgeKind(box: List<Float>, frameH: Int): CurbEdge? {
            if (box[3] / frameH < CurbWatcher.PLANE_BOTTOM_MAX) return CurbEdge.Up
            if (box[1] / frameH >= CurbWatcher.PLANE_TOP_MIN) return CurbEdge.Down
            return null
        }

        /**
         * Bateau : même filtre couloir/bande/distance que [nearCurb], mais pour
         * un plan continu **sans** bord proche (le sol se prolonge : pas de
         * marche, pas de danger). Distance + centre, `null` sinon.
         */
        private fun flatPass(reading: DistanceReading, frameW: Int, frameH: Int): Pair<Float, Float>? {
            if (!CurbConcepts.isGround(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            if (meters > CurbWatcher.CURB_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            if (box[3] / frameH < CurbWatcher.LOWER_BAND_FRACTION) return null
            if (edgeKind(box, frameH) != null) return null
            return meters to centerX
        }
    }
}

/**
 * Ordre d'annonce « trottoir » : [phrase] dans la langue de la voix, [meters]
 * la distance du signal qui l'a déclenché (journal, réglages), [tier] son
 * palier, [urgent] vrai pour les seuls paliers proches 0,7 / 0,5 m,
 * [safetyPhrase] l'alerte immédiate (« Bordure à 0,5 mètre. ») pour le seul
 * palier 0,5 m (`null` pour la détection, les paliers doux et le bateau —
 * info douce, pas d'alerte).
 */
data class CurbCue(
    val phrase: String,
    val meters: Float?,
    val tier: CurbTier = CurbTier.FAR,
    val urgent: Boolean = false,
    val safetyPhrase: String? = null,
)

/**
 * Phrases bordure, FR/EN comme les positions (`PositionText`) : la détection
 * (« Bordure détectée » / « Curb detected », avec l'heure quand le centre
 * est connu — c'est elle qui situe le bord), le compte à rebours 2 / 1,5 /
 * 1 / 0,7 / 0,5 m (« Bordure à … » / « Curb at … », sans heure : courts
 * pour s'enchaîner) et le bateau (« Bateau, pas de marche », doux, une
 * fois). Liste fermée : le préchauffage hors-ligne sait quels clips générer.
 */
object CurbText {
    /** Détection confirmée : « Bordure détectée, à midi. » (`null` = sans heure). */
    fun detectedPhrase(language: SpeechLanguage, centerX: Float? = null): String {
        val base = when (language) {
            SpeechLanguage.Fr -> "Bordure détectée"
            SpeechLanguage.En -> "Curb detected"
        }
        return PositionText.withHour(base, centerX, language)
    }

    /**
     * Palier du compte à rebours : « Bordure à 2 mètres. » — [palier] vaut
     * toujours un [CurbWatcher.COUNTDOWN_METERS] ; toute autre distance se
     * dit quand même (virgule FR, accord), jamais d'exception.
     */
    fun distancePhrase(palier: Float, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Bordure à ${frenchMeters(palier)}."
        SpeechLanguage.En -> "Curb at ${englishMeters(palier)}."
    }

    /** « 2 mètres », « 1,5 mètre » (singulier sous 2 m, virgule décimale). */
    private fun frenchMeters(meters: Float): String {
        val digits = digits(meters).replace('.', ',')
        return if (meters >= 2f) "$digits mètres" else "$digits mètre"
    }

    /** « 2 meters », « 1 meter » (pluriel sauf 1 m). */
    private fun englishMeters(meters: Float): String {
        val digits = digits(meters)
        return if (meters == 1f) "$digits meter" else "$digits meters"
    }

    /** « 2 », « 1.5 » : l'entier sans décimale, le reste brut (point, la virgule est FR). */
    private fun digits(meters: Float): String {
        val whole = meters.toInt()
        return if (meters == whole.toFloat()) whole.toString() else meters.toString()
    }

    /** Bateau : « Bateau, pas de marche. » — plan continu, pas de danger, dit une fois. */
    fun flatPhrase(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Bateau, pas de marche."
        SpeechLanguage.En -> "Dropped curb, no step."
    }
}

/**
 * Lissage temporel du [CurbSignal], miroir de `StairWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs, puis
 * silence pendant [cooldownMs] (un trottoir ne bouge pas).
 *
 * Détection dite aussitôt confirmée (« Bordure détectée. », une fois par
 * approche), puis compte à rebours [COUNTDOWN_METERS] (2 / 1,5 / 1 / 0,7 /
 * 0,5 m) : chaque palier dit une fois par approche dès [confirmFrames]
 * frames sous le seuil, **sans** cooldown entre paliers — l'approche à pied
 * les enchaîne en ~2 s, un cooldown les tuerait. Verrous + hystérésis
 * ([REARM_MARGIN_METERS] : recul au-delà = le palier reparle) tiennent le
 * silence à la place ; palier survolé entre deux frames = sauté sans
 * rattrapage (jamais de distance périmée) ; signal perdu = détection et
 * paliers ré-armés. Sans distance, le compte à rebours attend.
 * Le bateau ([CurbSignal.flatPass]) se dit doux, une fois par présence, jamais
 * juste après un palier proche (traversée du bord, pas un bateau).
 *
 * `sidewalk` segmente le plan entier, pas le bord : marcher **sur** un
 * trottoir ne parle pas d'un bord — le plan sous les pieds (bas collé en bas
 * de l'image, haut vers l'horizon) est un bateau, seuls les plans à bord
 * proche parlent (montée : bas décollé ; descente : haut borné). Seuils
 * priors, à valider sur captures du téléphone : en cas de doute le bord
 * parle, jamais l'inverse.
 *
 * Pur JVM et testé. Sécurité : le palier 0,5 m parle en alerte immédiate
 * ([CurbCue.safetyPhrase], voix de sécurité qui coupe tout, dictée du user
 * comprise) — détection, paliers doux et bateau attendent leur tour (pas
 * d'alerte, juste l'info).
 */
class CurbWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    /** Détections consécutives en cours (un trou remet à zéro). */
    private var hits: Int = 0

    /** Frames consécutives sous le palier visé (autre chose remet à zéro). */
    private var palierHits: Int = 0

    /** Palier visé en cours de confirmation, `null` si aucun. */
    private var palierTarget: Float? = null

    /** Paliers déjà dits (ou sautés car survolés) de l'approche en cours. */
    private val spokenPaliers = mutableSetOf<Float>()

    /** Bateaux consécutifs en cours (autre chose remet à zéro). */
    private var flatHits: Int = 0

    /** Détection déjà dite pour la présence en cours. */
    private var detectedSpoken: Boolean = false

    /** Bateau déjà dit pour la présence en cours. */
    private var flatSpoken: Boolean = false

    /** Instant du dernier palier proche dit (0,7 / 0,5 m), [NO_INSTANT] si aucun. */
    private var lastNearAtMs: Long = NO_INSTANT

    /** Instant de la dernière annonce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /** Dernier palier proche, `null` si aucun (P2 : le poteau attend quand il est frais). */
    val lastNearMs: Long?
        get() = lastNearAtMs.takeIf { it != NO_INSTANT }

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint ([CurbCue.urgent] pour les
     * seuls paliers proches), `null` sinon.
     */
    fun onSignal(signal: CurbSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): CurbCue? {
        if (signal.detected) {
            flatHits = 0
            flatSpoken = false
            return onDetected(signal, nowMs, language)
        }
        hits = 0
        palierHits = 0
        palierTarget = null
        detectedSpoken = false
        spokenPaliers.clear()
        if (!signal.flatPass) {
            flatHits = 0
            flatSpoken = false
            return null
        }
        // Bord traversé : le plan sous les pieds n'est pas un bateau.
        if (lastNearAtMs != NO_INSTANT && nowMs - lastNearAtMs < FLAT_AFTER_STEP_SUPPRESS_MS) return null
        flatHits++
        if (flatHits < confirmFrames) return null
        if (flatSpoken) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        flatSpoken = true
        lastCueAtMs = nowMs
        flatHits = 0
        return CurbCue(CurbText.flatPhrase(language), signal.meters, CurbTier.NONE, urgent = false)
    }

    /**
     * Bordure en vue : le recul ré-arme les paliers dépassés, puis la
     * détection parle (une fois), puis le compte à rebours.
     */
    private fun onDetected(signal: CurbSignal, nowMs: Long, language: SpeechLanguage): CurbCue? {
        val meters = signal.meters
        if (meters != null) spokenPaliers.removeAll { palier -> meters > palier + REARM_MARGIN_METERS }
        if (!detectedSpoken) return onDetection(signal, nowMs, language)
        return onCountdown(signal, nowMs, language)
    }

    /** Détection : parle vite, une fois par approche (le clignotement attend le cooldown). */
    private fun onDetection(signal: CurbSignal, nowMs: Long, language: SpeechLanguage): CurbCue? {
        palierHits = 0
        palierTarget = null
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        detectedSpoken = true
        lastCueAtMs = nowMs
        hits = 0
        return CurbCue(
            CurbText.detectedPhrase(language, signal.centerX),
            signal.meters,
            CurbTier.FAR,
            urgent = false,
            safetyPhrase = null,
        )
    }

    /**
     * Compte à rebours : le palier visé est le plus proche restant à dire
     * sous lequel on est passé ; les paliers survolés sont verrouillés muets
     * (périmés), le recul les ré-arme (voir [onDetected]).
     */
    private fun onCountdown(signal: CurbSignal, nowMs: Long, language: SpeechLanguage): CurbCue? {
        val meters = signal.meters ?: return null
        val target = COUNTDOWN_METERS.filter { it >= meters && it !in spokenPaliers }.minOrNull()
        if (target == null) {
            palierHits = 0
            palierTarget = null
            return null
        }
        spokenPaliers.addAll(COUNTDOWN_METERS.filter { it > target })
        if (target != palierTarget) {
            palierTarget = target
            palierHits = 1
            return null
        }
        palierHits++
        if (palierHits < confirmFrames) return null
        palierTarget = null
        palierHits = 0
        spokenPaliers.add(target)
        lastCueAtMs = nowMs
        val near = target <= STEP_NEAR_METERS
        if (near) lastNearAtMs = nowMs
        val phrase = CurbText.distancePhrase(target, language)
        return CurbCue(
            phrase,
            signal.meters,
            if (near) CurbTier.NEAR else CurbTier.FAR,
            urgent = near,
            safetyPhrase = phrase.takeIf { target == SAFETY_PALIER_METERS },
        )
    }

    /** Oublie les comptes, les verrous et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        palierHits = 0
        palierTarget = null
        spokenPaliers.clear()
        flatHits = 0
        detectedSpoken = false
        flatSpoken = false
        lastNearAtMs = NO_INSTANT
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces douces (le trottoir ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), un bord n'est même plus suivi (détection dite dès confirmée). */
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

        /** Sous cette distance lissée (m), le bord est proche : parole urgente. */
        const val STEP_NEAR_METERS = 0.7f

        /** Paliers du compte à rebours (m), du plus loin au plus proche. */
        val COUNTDOWN_METERS = listOf(2.0f, 1.5f, 1.0f, 0.7f, 0.5f)

        /**
         * Hystérésis : un palier dit se ré-arme quand on recule au-delà de
         * T + marge (chaque palier se redit en s'approchant à nouveau).
         */
        const val REARM_MARGIN_METERS = 0.2f

        /** Palier le plus proche : parlé en alerte immédiate (voix de sécurité). */
        const val SAFETY_PALIER_METERS = 0.5f

        /** Après un palier proche dit, le bateau reste muet (traversée du bord). */
        const val FLAT_AFTER_STEP_SUPPRESS_MS = 10_000L

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
