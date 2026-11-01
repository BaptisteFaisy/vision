package dev.sam3vision.core.curb

import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Palier de proximité d'un bord : [NONE] rien en vue, [FAR] pré-alerte douce
 * (≤ [CurbWatcher.CURB_METERS]), [NEAR] marche imminente, parole urgente
 * (distance lissée ≤ [CurbWatcher.STEP_NEAR_METERS]).
 */
enum class CurbTier { NONE, FAR, NEAR }

/**
 * Sens du bord : [Up] montée (lever le pied), [Down] descente, [Uncertain]
 * sens incertain (zone grise, chemin/route : « Attention bordure »).
 */
enum class CurbEdge { Up, Down, Uncertain }

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
 * pour s'enchaîner), les marches sensées ([phrase] à 1 m, [stepPhrase]
 * imminente, [safetyStepPhrase] consigne — montée, descente ou sens
 * incertain, reel audit 31/10) et le bateau (« Bateau, pas de marche »,
 * doux, une fois). Liste fermée : le préchauffage hors-ligne sait quels
 * clips générer.
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

    /** Pré-alerte douce à 1 m, sensée : « Marche dans 1 mètre, à midi. » (`null` = sans heure) ; sens incertain : « Attention bordure ». */
    fun phrase(edge: CurbEdge, language: SpeechLanguage, centerX: Float? = null): String {
        val base = when (language) {
            SpeechLanguage.Fr -> when (edge) {
                CurbEdge.Up -> "Marche dans 1 mètre"
                CurbEdge.Uncertain -> "Attention bordure"
                CurbEdge.Down -> "Descente dans 1 mètre"
            }
            SpeechLanguage.En -> when (edge) {
                CurbEdge.Up -> "Step up in 1 meter"
                CurbEdge.Uncertain -> "Attention, curb"
                CurbEdge.Down -> "Step down in 1 meter"
            }
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

    /** Marche imminente à 0,7 m, sans heure : « Step up now. » en montée, « Step down now. » en descente, « Attention bordure. » si le sens est incertain. */
    fun stepPhrase(edge: CurbEdge, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> when (edge) {
            CurbEdge.Up -> "Marche, maintenant."
            CurbEdge.Uncertain -> "Attention bordure."
            CurbEdge.Down -> "Descente, maintenant."
        }
        SpeechLanguage.En -> when (edge) {
            CurbEdge.Up -> "Step up now."
            CurbEdge.Uncertain -> "Attention, curb."
            CurbEdge.Down -> "Step down now."
        }
    }

    /** Bateau : « Bateau, pas de marche. » — plan continu, pas de danger, dit une fois. */
    fun flatPhrase(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Bateau, pas de marche."
        SpeechLanguage.En -> "Dropped curb, no step."
    }

    /**
     * Consigne immédiate d'un bord détecté : monter ou descendre le rebord
     * (« Attention bordure » si le sens est incertain), dans la langue de la
     * voix — jamais de « Stop » (ordre user
     * du 31/10 : le stop ne se dit qu'en Traversée, voitures en
     * mouvement). Parlée par la voix de sécurité, qui coupe tout, dictée
     * du user comprise.
     */
    fun safetyStepPhrase(edge: CurbEdge, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> when (edge) {
            CurbEdge.Up -> "Montez le rebord."
            CurbEdge.Uncertain -> "Attention bordure."
            CurbEdge.Down -> "Descendez le rebord."
        }
        SpeechLanguage.En -> when (edge) {
            CurbEdge.Up -> "Step up."
            CurbEdge.Uncertain -> "Attention, curb."
            CurbEdge.Down -> "Step down."
        }
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
 * rattrapage (jamais de distance périmée) ; signal vraiment perdu (3 trous, voir
 * [GRACE_FRAMES]) = détection et paliers ré-armés. Sans distance, le compte à rebours attend.
 * Le bateau ([CurbSignal.flatPass]) se dit doux, une fois par présence, jamais
 * juste après un palier proche (traversée du bord, pas un bateau).
 *
 * `sidewalk` segmente le plan entier, pas le bord : marcher **sur** un
 * trottoir ne parle pas d'un bord — le plan sous les pieds (bas collé en bas
 * de l'image, haut vers l'horizon) est un bateau, seuls les plans à bord
 * proche parlent (montée : bas décollé ; descente : haut borné). Seuils
 * priors, à valider sur captures du téléphone : en cas de doute le bord
 * parle (« Attention bordure » en zone grise), jamais l'inverse.
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
    /** Détections consécutives en cours (3 trous remettent à zéro, voir [GRACE_FRAMES]). */
    private var hits: Int = 0

    /** Frames non-détectées consécutives (clignotement SAM, voir [GRACE_FRAMES]). */
    private var misses: Int = 0

    /** Frames consécutives sous le palier visé (autre palier remet à zéro, trous graciés voir [GRACE_FRAMES]). */
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

    /** Dernier palier proche, `null` si aucun (P2 : la personne attend quand il est frais). */
    val lastNearMs: Long?
        get() = lastNearAtMs.takeIf { it != NO_INSTANT }

    /**
     * Alimente le lissage avec le [signal] de la frame à [nowMs] : l'annonce
     * quand le compte de confirmation est atteint ([CurbCue.urgent] pour les
     * seuls paliers proches), `null` sinon.
     */
    fun onSignal(signal: CurbSignal, nowMs: Long, language: SpeechLanguage = SpeechLanguage.DEFAULT): CurbCue? {
        if (signal.detected) {
            misses = 0
            flatHits = 0
            flatSpoken = false
            return onDetected(signal, nowMs, language)
        }
        // Clignotement SAM nominal : 1-2 trous gardent l'approche en cours (voir GRACE_FRAMES).
        if (misses < GRACE_FRAMES && hasPendingDetection()) {
            misses++
            return null
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
     * Approche en cours : détection en confirmation ([hits]), dite ([detectedSpoken]),
     * ou paliers en jeu. Seuls les trous d'une approche sont graciés (voir [GRACE_FRAMES]) :
     * au repos, le traitement historique tourne et le bateau parle comme avant.
     */
    private fun hasPendingDetection(): Boolean =
        hits != 0 || palierHits != 0 || palierTarget != null || detectedSpoken || spokenPaliers.isNotEmpty()

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

    /** Détection : parle vite, une fois par approche (le clignotement attend le cooldown). Sens incertain (chemin/route) : consigne prudente, pas la détection franche. */
    private fun onDetection(signal: CurbSignal, nowMs: Long, language: SpeechLanguage): CurbCue? {
        palierHits = 0
        palierTarget = null
        hits++
        if (hits < confirmFrames) return null
        if (lastCueAtMs != NO_INSTANT && nowMs - lastCueAtMs < cooldownMs) return null
        detectedSpoken = true
        lastCueAtMs = nowMs
        hits = 0
        val edge = signal.edge ?: CurbEdge.Up
        val phrase = if (edge == CurbEdge.Uncertain) CurbText.phrase(edge, language, signal.centerX)
        else CurbText.detectedPhrase(language, signal.centerX)
        return CurbCue(
            phrase,
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
        val edge = signal.edge ?: CurbEdge.Up
        val phrase = if (edge == CurbEdge.Uncertain) CurbText.stepPhrase(edge, language)
        else CurbText.distancePhrase(target, language)
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
        misses = 0
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

        /**
         * Tolérance au clignotement SAM : jusqu'à [GRACE_FRAMES] frames consécutives
         * non-détectées ([CurbSignal.detected] faux, plan bateau compris) ne réarment rien
         * (comptes, verrous et dernière annonce inchangés) et rendent `null` ; la 3e frame
         * consécutive sans détection ré-arme comme avant, puis le bateau se traite comme avant.
         * Sans cette grâce, un seul trou remettait la détection à zéro et la reprise retombait
         * sur le cooldown 10 s : compte à rebours inaccessible pendant toute une approche.
         */
        const val GRACE_FRAMES = 2

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

        /** Demi-largeur de la zone grise (fraction de hauteur) autour des seuils du plan : là, le sens est incertain. */
        const val EDGE_GRAY_BAND = 0.03f

        /** Sous cette distance lissée (m), le bord est imminent : parole urgente. */
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
