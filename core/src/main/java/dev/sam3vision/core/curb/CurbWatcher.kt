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
 * palier, [urgent] vrai pour la seule marche imminente (coupe la voix, comme
 * le stop ; tout le reste attend le silence), [safetyPhrase] l'alerte
 * immédiate (« Stop, … ») pour la seule marche imminente (`null` pour la
 * pré-alerte à 1 m comme pour le bateau — info douce, pas d'alerte).
 */
data class CurbCue(
    val phrase: String,
    val meters: Float?,
    val tier: CurbTier = CurbTier.FAR,
    val urgent: Boolean = false,
    val safetyPhrase: String? = null,
)

/**
 * Phrases trottoir, FR/EN comme les positions (`PositionText`) : la pré-alerte
 * douce à 1 m (« Marche dans 1 mètre » / « Step up in 1 meter », avec l'heure),
 * l'imminent à 0,7 m (« Marche, maintenant. » / « Step up now. », urgent, sans
 * heure : à un pas, l'heure retarde la consigne) et le bateau (« Bateau, pas
 * de marche », doux, une fois). Liste fermée : le préchauffage hors-ligne
 * sait quels clips générer.
 */
object CurbText {
    /** Pré-alerte douce à 1 m, sensée : « Marche dans 1 mètre, à midi. » (`null` = sans heure). */
    fun phrase(edge: CurbEdge, language: SpeechLanguage, centerX: Float? = null): String {
        val base = when (language) {
            SpeechLanguage.Fr -> when (edge) {
                CurbEdge.Up -> "Marche dans 1 mètre"
                CurbEdge.Down -> "Descente dans 1 mètre"
            }
            SpeechLanguage.En -> when (edge) {
                CurbEdge.Up -> "Step up in 1 meter"
                CurbEdge.Down -> "Step down in 1 meter"
            }
        }
        return PositionText.withHour(base, centerX, language)
    }

    /** Marche imminente à 0,7 m, sans heure : « Step up now. » en montée, « Step down now. » sinon. */
    fun stepPhrase(edge: CurbEdge, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> when (edge) {
            CurbEdge.Up -> "Marche, maintenant."
            CurbEdge.Down -> "Descente, maintenant."
        }
        SpeechLanguage.En -> when (edge) {
            CurbEdge.Up -> "Step up now."
            CurbEdge.Down -> "Step down now."
        }
    }

    /** Bateau : « Bateau, pas de marche. » — plan continu, pas de danger, dit une fois. */
    fun flatPhrase(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Bateau, pas de marche."
        SpeechLanguage.En -> "Dropped curb, no step."
    }

    /**
     * Alerte immédiate d'un bord détecté : « Stop, … » montant ou descendant,
     * dans la langue de la voix. Parlée par la voix de sécurité, qui coupe
     * tout, dictée du user comprise.
     */
    fun safetyStepPhrase(edge: CurbEdge, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> when (edge) {
            CurbEdge.Up -> "Stop, rebord montant."
            CurbEdge.Down -> "Stop, rebord descendant."
        }
        SpeechLanguage.En -> when (edge) {
            CurbEdge.Up -> "Stop, step up."
            CurbEdge.Down -> "Stop, step down."
        }
    }
}

/**
 * Lissage temporel du [CurbSignal], miroir de `StairWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs (dont
 * [NEAR_CONFIRM_FRAMES] pour l'imminent), puis silence pendant [cooldownMs]
 * (un trottoir ne bouge pas).
 *
 * Deux paliers annoncés : la pré-alerte [CurbTier.FAR] à 1 m (douce, une fois
 * par approche) puis la marche imminente [CurbTier.NEAR] à 0,7 m (compteur
 * séparé, parole urgente même en cooldown : la première vue parle). Le suivi
 * reste actif jusqu'à [CURB_METERS] mais n'annonce plus au-delà de
 * [FAR_ANNOUNCE_METERS]. Hystérésis 1,0 / 1,4 m (pré-alerte) et 0,7 / 0,9 m
 * (imminent) : après une annonce, il faut s'éloigner au-delà de
 * [FAR_REARM_METERS] / [STEP_REARM_METERS] ou perdre le signal pour ré-armer.
 * Le bateau ([CurbSignal.flatPass]) se dit doux, une fois par présence, jamais
 * juste après une marche (traversée du bord, pas un bateau).
 *
 * `sidewalk` segmente le plan entier, pas le bord : marcher **sur** un
 * trottoir ne parle pas d'un bord — le plan sous les pieds (bas collé en bas
 * de l'image, haut vers l'horizon) est un bateau, seuls les plans à bord
 * proche parlent (montée : bas décollé ; descente : haut borné). Seuils
 * priors, à valider sur captures du téléphone : en cas de doute le bord
 * parle, jamais l'inverse.
 *
 * Pur JVM et testé. Sécurité : la marche imminente parle en alerte immédiate
 * ([CurbCue.safetyPhrase], voix de sécurité qui coupe tout, dictée du user
 * comprise) — la pré-alerte à 1 m et le bateau attendent le silence (pas
 * d'alerte, juste l'info).
 */
class CurbWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nearConfirmFrames: Int = NEAR_CONFIRM_FRAMES,
) {
    /** Pré-alertes consécutives en cours (autre chose remet à zéro). */
    private var hits: Int = 0

    /** Imminents consécutifs en cours (autre chose remet à zéro). */
    private var nearHits: Int = 0

    /** Bateaux consécutifs en cours (autre chose remet à zéro). */
    private var flatHits: Int = 0

    /** Imminent déjà dit, en attente de ré-armement (> 0,9 m ou signal perdu). */
    private var nearSpoken: Boolean = false

    /** Pré-alerte déjà dite, en attente de ré-armement (> 1,4 m ou signal perdu). */
    private var farSpoken: Boolean = false

    /** Bateau déjà dit pour la présence en cours. */
    private var flatSpoken: Boolean = false

    /** Instant de la dernière marche imminente dite, [NO_INSTANT] si aucune. */
    private var lastNearAtMs: Long = NO_INSTANT

    /** Instant de la dernière annonce, [NO_INSTANT] si aucune. */
    private var lastCueAtMs: Long = NO_INSTANT

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = lastCueAtMs.takeIf { it != NO_INSTANT }

    /** Dernière marche imminente, `null` si aucune (P2 : le poteau attend quand elle est fraîche). */
    val lastNearMs: Long?
        get() = lastNearAtMs.takeIf { it != NO_INSTANT }

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint ([CurbCue.urgent] pour le
     * seul imminent), `null` sinon.
     */
    fun onSignal(signal: CurbSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): CurbCue? {
        if (signal.detected) {
            flatHits = 0
            flatSpoken = false
            return if (signal.tier == CurbTier.NEAR) onNear(signal, nowMs, language)
            else onFar(signal, nowMs, language)
        }
        hits = 0
        nearHits = 0
        nearSpoken = false
        farSpoken = false
        if (!signal.flatPass) {
            flatHits = 0
            flatSpoken = false
            return null
        }
        // Marche traversée : le plan sous les pieds n'est pas un bateau.
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

    /** Imminent à 0,7 m : parle vite, urgent, même en cooldown (la première vue parle). */
    private fun onNear(signal: CurbSignal, nowMs: Long, language: SpeechLanguage): CurbCue? {
        hits = 0
        nearHits++
        if (nearHits < nearConfirmFrames) return null
        if (nearSpoken) return null
        nearSpoken = true
        farSpoken = true
        lastCueAtMs = nowMs
        lastNearAtMs = nowMs
        nearHits = 0
        val edge = signal.edge ?: CurbEdge.Up
        return CurbCue(
            CurbText.stepPhrase(edge, language),
            signal.meters,
            CurbTier.NEAR,
            urgent = true,
            safetyPhrase = CurbText.safetyStepPhrase(edge, language),
        )
    }

    /**
     * Pré-alerte douce à 1 m, une fois par approche ; entre 1 et 3 m le suivi
     * reste silencieux. Au-delà des réarmements, l'imminent puis la pré-alerte
     * se ré-arment.
     */
    private fun onFar(signal: CurbSignal, nowMs: Long, language: SpeechLanguage): CurbCue? {
        nearHits = 0
        val meters = signal.meters
        if (meters != null && meters > STEP_REARM_METERS) nearSpoken = false
        if (meters != null && meters > FAR_REARM_METERS) farSpoken = false
        if (meters == null || meters > FAR_ANNOUNCE_METERS) {
            hits = 0
            return null
        }
        if (farSpoken) return null
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        farSpoken = true
        lastCueAtMs = nowMs
        hits = 0
        val edge = signal.edge ?: CurbEdge.Up
        return CurbCue(
            CurbText.phrase(edge, language, signal.centerX),
            signal.meters,
            CurbTier.FAR,
            urgent = false,
            safetyPhrase = null,
        )
    }

    /** Oublie les comptes, les verrous et l'annonce (nouvelle séance de direct). */
    fun reset() {
        hits = 0
        nearHits = 0
        flatHits = 0
        nearSpoken = false
        farSpoken = false
        flatSpoken = false
        lastNearAtMs = NO_INSTANT
        lastCueAtMs = NO_INSTANT
    }

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces douces (le trottoir ne bouge pas). */
        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), un bord n'est même plus suivi (annonce dès 1 m). */
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

        /** Sous cette distance lissée (m), le bord est imminent : parole urgente. */
        const val STEP_NEAR_METERS = 0.7f

        /** Au-delà (m), l'imminent se ré-arme : hystérésis 0,7 / 0,9. */
        const val STEP_REARM_METERS = 0.9f

        /** Sous cette distance (m), la pré-alerte parle ; au-delà, suivi silencieux. */
        const val FAR_ANNOUNCE_METERS = 1.0f

        /** Au-delà (m), la pré-alerte se ré-arme : hystérésis 1,0 / 1,4. */
        const val FAR_REARM_METERS = 1.4f

        /** Après une marche dite, le bateau reste muet (traversée du bord). */
        const val FLAT_AFTER_STEP_SUPPRESS_MS = 10_000L

        /** Imminents consécutifs avant de parler (la première vue parle vite). */
        const val NEAR_CONFIRM_FRAMES = 2

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
