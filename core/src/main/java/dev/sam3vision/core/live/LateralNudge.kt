package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import kotlin.math.abs

/**
 * Consigne de décalage latéral : un obstacle proche conseille de se décaler
 * vers [side] (la direction **où aller**, jamais le côté de l'obstacle).
 *
 * Audit foule du 01/11/2026 : la consigne n'était qu'un couple (côté, mètres),
 * et un seuil en mètres ne dit rien du temps — à 2,8 m/s de fermeture la
 * latence de décision vaut 1,7-2,0 m, donc un mot déclenché « à 2 m » arrive
 * au contact. D'où :
 * - [ttcS] : temps de contact projeté (`null` = inconnu ou immobile — ce n'est
 *   pas une urgence, on ne la prétend pas) ;
 * - [tight] : le côté conseillé n'a pas la marge d'espace libre (l'appelant dit
 *   « serrez » au lieu de « tournez ») ;
 * - [id] : piste visée quand l'appelant en tient une (une seule alerte par
 *   personne).
 *
 * [meters] est la distance **projetée à l'instant de la décision** de la menace
 * la plus proche (pas la distance brute de la frame : [Timing.projectDistance]).
 */
data class LateralNudge(
    val side: SteeringCap,
    val meters: Float,
    val ttcS: Float? = null,
    val tight: Boolean = false,
    val id: Int? = null,
)

/**
 * Suit les obstacles par côté avant (gauche/droite de l'axe marché) et rend la
 * consigne de décalage de la frame. Pur JVM, horloge explicite (`atMs`, `ageMs`
 * passés) : aucune lecture d'horloge implicite.
 *
 * ### Ce que l'audit foule du 01/11/2026 a mesuré, et que ce tracker corrige
 * - **Muet 90-94 % des frames** alors qu'un piéton est dans les 4 m : l'ancien
 *   code rendait `null` quand les **deux** côtés avaient un obstacle (cas
 *   nominal en foule) et quand une personne se tenait côté cible (veto) ;
 * - **seuils en mètres** (`CLOSE_METERS = 1.5`) alors qu'à 2,8 m/s de fermeture
 *   la latence vaut 1,7-2,0 m : le mot déclenché à 2 m arrive au contact ;
 * - **aucun état ne dit « ralentis »** quand il n'y a pas de passage ;
 * - **rien ne bornait le côté conseillé à l'espace réel** : ordre à 1,38 m à
 *   gauche sur un trottoir de 1,8 m ;
 * - **88 changements de mot en 120 s** : le côté clignotait d'une frame à
 *   l'autre.
 *
 * ### Règles tenues ici
 * 1. **Jamais muet par ambiguïté** : deux côtés occupés ⇒ on conseille le côté
 *    qui a le plus de place ([FreeSpace]) — jamais `null` par simple doute ;
 * 2. **veto personne** : une personne côté visé n'éteint plus la consigne, elle
 *    inverse le côté s'il est libre de personne, sinon elle garde le côté visé
 *    avec [LateralNudge.tight] (`true` = « serrez », on ne fonce pas) ;
 * 3. **TTC** : décision jugée sur [Timing.projectDistance] puis
 *    [Timing.ttcSeconds] — sous [Timing.URGENT_TTC_S] (1,2 s) la consigne sort
 *    **dès la première frame**, entre 1,2 s et [Timing.SOON_TTC_S] (2,2 s) dès
 *    que l'approche est confirmée ([CONFIRM_DECREASES] diminutions ou piste
 *    connue), au-delà seulement sous [CLOSE_METERS] (compatibilité) ;
 * 4. **hystérésis** : un côté choisi est tenu au moins [MIN_HOLD_MS], on ne
 *    change que si l'autre côté offre [SWITCH_GAIN_M] de dégagement en plus ou
 *    si l'obstacle qui motivait la tenue a disparu ;
 * 5. **espace** : un côté sans marge ([FreeSpace.EDGE_MARGIN_M]) est marqué
 *    [LateralNudge.tight] — on ne renvoie jamais un côté qui n'existe pas sans
 *    le dire.
 *
 * Fenêtre d'évitement (ordre user du 30/10) : seuls les obstacles dans les
 * [AVOID_HALF_WIDTH_M] de l'axe conseillent un décalage — au-delà, ils passent
 * au large. Humains, objets, poteaux : tout obstacle ([Guidance.isObstacle]).
 */
class LateralNudgeTracker {

    /** Mesure d'un côté : distance brute, fermeture, piste, confirmations. */
    private class Reading(
        val meters: Float,
        val closing: Float?,
        val id: Int?,
        val hits: Int,
    )

    /** Choix de la frame : direction, dégagement offert, marge suffisante ? */
    private class Decision(val side: SteeringCap, val room: Float, val tight: Boolean)

    /** Mémoire d'un côté : dernière distance, confirmations, fermeture vue. */
    private class SideTrack {
        var meters: Float = Float.POSITIVE_INFINITY
        var hits: Int = 0
        var lastMs: Long = Long.MIN_VALUE
        var closing: Float? = null

        fun forget() {
            meters = Float.POSITIVE_INFINITY
            hits = 0
            lastMs = Long.MIN_VALUE
            closing = null
        }

        /**
         * Enregistre [meters] à [atMs] et rend la fermeture déduite des frames
         * (`null` à la première observation : on ne devine pas un mouvement
         * qu'on n'a pas vu). La première observation ne compte pas comme une
         * diminution.
         */
        fun observe(atMs: Long, meters: Float): Float? {
            val previous = this.meters
            val dtS = if (lastMs == Long.MIN_VALUE) 0f else (atMs - lastMs) / 1_000f
            val seen = if (previous.isFinite() && dtS > 0f) (previous - meters) / dtS else null
            if (previous.isFinite()) {
                if (meters < previous - LateralNudgeTracker.MIN_DECREASE_M) hits += 1
                else if (meters > previous + LateralNudgeTracker.MIN_DECREASE_M) hits = 0
            }
            this.meters = meters
            this.lastMs = atMs
            this.closing = seen
            return seen
        }
    }

    private val tracks = mapOf(
        SteeringCap.Left to SideTrack(),
        SteeringCap.Right to SideTrack(),
    )

    /** Côté tenu par l'hystérésis et instant du choix, `null` = aucune tenue. */
    private var heldSide: SteeringCap? = null
    private var heldSinceMs: Long = 0L

    /**
     * Consigne de la frame, `null` **uniquement** si aucun obstacle n'est à
     * portée (ou si l'approche n'est pas encore prouvée : voir règle 3).
     *
     * @param atMs horloge de la frame (ms, explicite)
     * @param labels objets placés de la frame (distance et piste optionnelles)
     * @param hfovDeg champ horizontal, pour calibrer la fenêtre en mètres
     * @param ageMs âge de la mesure ([Timing.projectDistance] : une frame de
     *   0,7 s décrit un monde qui a avancé)
     * @param speedMps vitesse de marche : la portée la suit ([Timing.rangeFor])
     * @param free espace libre par côté ([FreeSpace] ; `unknown()` = inconnu,
     *   on ne marque alors pas [LateralNudge.tight] sur l'espace seul)
     */
    fun onFrame(
        atMs: Long,
        labels: List<PlacedLabel>,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        ageMs: Long = 0L,
        speedMps: Float? = null,
        free: FreeSpace = FreeSpace.unknown(),
    ): LateralNudge? {
        val range = Timing.rangeFor(speedMps)
        val left = read(SteeringCap.Left, atMs, labels, hfovDeg, range)
        val right = read(SteeringCap.Right, atMs, labels, hfovDeg, range)
        if (left == null && right == null) {
            heldSide = null
            return null
        }
        val gate = listOfNotNull(left, right)
            .filter { passes(it, ageMs) }
            .minByOrNull { it.meters }
            ?: return null
        val base = wantedSide(left, right, free, range)
        val wanted = decisionFor(base, labels, free, hfovDeg, left, right, range)
        return nudgeOf(resolve(atMs, wanted, labels, free, hfovDeg, left, right, range), gate, ageMs)
    }

    /** Oublie les deux côtés et la tenue (nouvelle séance, fin de traversée). */
    fun reset() {
        tracks.values.forEach { it.forget() }
        heldSide = null
        heldSinceMs = 0L
    }

    /**
     * Mesure d'un côté : obstacle le plus proche dans la fenêtre et à portée,
     * fermeture (donnée par la piste ou déduite des frames), confirmations.
     */
    private fun read(
        side: SteeringCap,
        atMs: Long,
        labels: List<PlacedLabel>,
        hfovDeg: Float,
        range: Float,
    ): Reading? {
        val track = tracks.getValue(side)
        val found = labels.asSequence()
            .filter { Guidance.isObstacle(it) && inFront(it, side, hfovDeg) }
            .mapNotNull { label ->
                label.meters?.takeIf { it.isFinite() && it > 0f && it <= range }?.let { label to it }
            }
            .minByOrNull { it.second }
        if (found == null) {
            if (track.lastMs != Long.MIN_VALUE && atMs - track.lastMs > STALE_MS) track.forget()
            return null
        }
        val (label, meters) = found
        val seen = track.observe(atMs, meters)
        val closing = label.closingMps?.takeIf { it.isFinite() } ?: seen
        return Reading(meters, closing, label.trackId, track.hits)
    }

    /**
     * Côté visé avant veto : les deux côtés occupés ⇒ le plus large
     * ([FreeSpace.side] quand l'espace départage, sinon la distance mesurée) ;
     * un seul côté occupé ⇒ l'autre.
     */
    private fun wantedSide(left: Reading?, right: Reading?, free: FreeSpace, range: Float): SteeringCap = when {
        left != null && right != null -> widest(left, right, free, range)
        left != null -> SteeringCap.Right
        else -> SteeringCap.Left
    }

    /**
     * Le côté qui a le plus de place : [FreeSpace.side] quand l'espace mesuré
     * départage les deux côtés, sinon le côté dont l'obstacle est le plus loin
     * (espace symétrique ou supposé : il n'informe pas). Égalité ⇒ droite, la
     * convention fixe de [FreeSpace.side].
     */
    private fun widest(left: Reading, right: Reading, free: FreeSpace, range: Float): SteeringCap {
        val roomLeft = free.room(GuidanceSide.Left)
        val roomRight = free.room(GuidanceSide.Right)
        if (roomLeft.isFinite() && roomRight.isFinite() && roomLeft != roomRight) return capOf(free.side)
        val clearLeft = clearance(SteeringCap.Left, left, right, range)
        val clearRight = clearance(SteeringCap.Right, left, right, range)
        return if (clearLeft > clearRight) SteeringCap.Left else SteeringCap.Right
    }

    /**
     * Décision pour un côté visé : le veto personne inverse le côté s'il est
     * libre de personne, sinon il tient le côté visé en marquant la contrainte
     * ([Decision.tight]) — on ne fonce pas, on serre.
     */
    private fun decisionFor(
        side: SteeringCap,
        labels: List<PlacedLabel>,
        free: FreeSpace,
        hfovDeg: Float,
        left: Reading?,
        right: Reading?,
        range: Float,
    ): Decision {
        val vetoed = personAhead(labels, side, hfovDeg)
        val chosen = if (vetoed && !personAhead(labels, opposite(side), hfovDeg)) opposite(side) else side
        val tight = vetoed || lacksRoom(free, chosen)
        return Decision(chosen, clearance(chosen, left, right, range), tight)
    }

    /** Applique l'hystérésis : le côté tenu garde la main tant qu'il se justifie. */
    private fun resolve(
        atMs: Long,
        wanted: Decision,
        labels: List<PlacedLabel>,
        free: FreeSpace,
        hfovDeg: Float,
        left: Reading?,
        right: Reading?,
        range: Float,
    ): Decision {
        val current = heldSide
        if (current == null) {
            hold(wanted.side, atMs)
            return wanted
        }
        if (current == wanted.side) return wanted
        if (releaseHold(atMs, current, wanted, left, right, free, range)) {
            hold(wanted.side, atMs)
            return wanted
        }
        return decisionFor(current, labels, free, hfovDeg, left, right, range)
    }

    /**
     * Vrai si la tenue doit être levée : l'obstacle qui la motivait (de l'autre
     * côté) a disparu — sinon il faut [MIN_HOLD_MS] écoulé **et**
     * [SWITCH_GAIN_M] de dégagement mesuré en plus pour l'autre côté.
     */
    private fun releaseHold(
        atMs: Long,
        current: SteeringCap,
        wanted: Decision,
        left: Reading?,
        right: Reading?,
        free: FreeSpace,
        range: Float,
    ): Boolean {
        if (readingAt(opposite(current), left, right) == null) return true
        if (atMs - heldSinceMs < MIN_HOLD_MS) return false
        return wanted.room - clearance(current, left, right, range) >= SWITCH_GAIN_M
    }

    private fun hold(side: SteeringCap, atMs: Long) {
        heldSide = side
        heldSinceMs = atMs
    }

    /** Consigne finale : côté + marge, sur la distance **projetée** de la menace. */
    private fun nudgeOf(decision: Decision, threat: Reading, ageMs: Long): LateralNudge? {
        val distance = Timing.projectDistance(threat.meters, threat.closing, ageMs) ?: return null
        return LateralNudge(
            side = decision.side,
            meters = distance,
            ttcS = Timing.ttcSeconds(distance, threat.closing),
            tight = decision.tight,
            id = threat.id,
        )
    }

    /**
     * Garde de parole (règle 3) : urgence (TTC sous [Timing.URGENT_TTC_S]) ⇒
     * tout de suite ; approche confirmée sous [Timing.SOON_TTC_S] ; au-delà,
     * seulement si l'obstacle est sous [CLOSE_METERS] (compatibilité).
     */
    private fun passes(reading: Reading, ageMs: Long): Boolean {
        val distance = Timing.projectDistance(reading.meters, reading.closing, ageMs) ?: return false
        val ttc = Timing.ttcSeconds(distance, reading.closing)
        if (Timing.isUrgent(ttc)) return true
        val confirmed = reading.hits >= CONFIRM_DECREASES || reading.id != null
        if (ttc != null && ttc <= Timing.SOON_TTC_S && confirmed) return true
        return distance <= CLOSE_METERS
    }

    /**
     * Dégagement offert par [side] (m) : distance de l'obstacle de ce côté, ou
     * la portée s'il n'y en a aucun. Mesure ce que le tracker voit — l'espace
     * de [FreeSpace] parle des bords, pas des personnes qui arrivent.
     */
    private fun clearance(side: SteeringCap, left: Reading?, right: Reading?, range: Float): Float =
        readingAt(side, left, right)?.meters ?: range

    /** Vrai si [free] dit que le côté [side] n'a pas la marge de trottoir. */
    private fun lacksRoom(free: FreeSpace, side: SteeringCap): Boolean {
        val room = free.room(guidanceSide(side))
        return room.isFinite() && room < FreeSpace.EDGE_MARGIN_M
    }

    private fun readingAt(side: SteeringCap, left: Reading?, right: Reading?): Reading? =
        if (side == SteeringCap.Left) left else right

    private fun guidanceSide(side: SteeringCap): GuidanceSide =
        if (side == SteeringCap.Left) GuidanceSide.Left else GuidanceSide.Right

    private fun capOf(side: GuidanceSide): SteeringCap =
        if (side == GuidanceSide.Left) SteeringCap.Left else SteeringCap.Right

    private fun opposite(side: SteeringCap): SteeringCap =
        if (side == SteeringCap.Left) SteeringCap.Right else SteeringCap.Left

    /**
     * Vrai si une personne se tient côté [side] : mesurée dans la fenêtre, ou
     * non mesurée de ce côté (prudence : l'inconnu bloque le côté) — s'écarter
     * vers elle serait foncer dedans.
     */
    private fun personAhead(labels: List<PlacedLabel>, side: SteeringCap, hfovDeg: Float): Boolean =
        labels.any { PersonLabels.isPerson(it.label) && (inFront(it, side, hfovDeg) || unmeasuredAside(it, side)) }

    /** Personne non mesurée côté [side] (sans géométrie : bloque, prudence). */
    private fun unmeasuredAside(label: PlacedLabel, side: SteeringCap): Boolean {
        if (label.meters != null && label.meters.isFinite() && label.meters > 0f) return false
        if (!label.centerX.isFinite()) return true
        return if (side == SteeringCap.Left) label.centerX <= SIDE_MID else label.centerX >= SIDE_MID
    }

    /**
     * Obstacle du côté [side], dans la fenêtre : écart latéral à l'axe
     * ([lateralMeters]) sous [AVOID_HALF_WIDTH_M], à gauche ou à droite de
     * l'axe ([SIDE_MID]). Sans distance mesurée, pas de fenêtre en mètres :
     * on se tait (l'inconnu bloque le couloir, il ne conseille pas).
     */
    private fun inFront(label: PlacedLabel, side: SteeringCap, hfovDeg: Float): Boolean {
        val depth = label.meters?.takeIf { it.isFinite() && it > 0f } ?: return false
        if (abs(lateralMeters(label.centerX, depth, hfovDeg)) > AVOID_HALF_WIDTH_M) return false
        return if (side == SteeringCap.Left) label.centerX < SIDE_MID else label.centerX > SIDE_MID
    }

    /** Écart latéral à l'axe (m, signé : négatif à gauche) à [depth] de profondeur. */
    private fun lateralMeters(centerX: Float, depth: Float, hfovDeg: Float): Float =
        (centerX - SIDE_MID) * 2f * depth * LensSpec.halfTangent(hfovDeg)

    companion object {
        /**
         * Portée historique (m), gardée pour les appelants qui la lisent
         * ([dev.sam3vision.core.crossing.CrossingOnPath]). La portée réelle de
         * la décision est [Timing.rangeFor] : 4,06 m au pas de référence, plus
         * loin à vive allure.
         */
        const val NUDGE_METERS = Guidance.RANGE_METERS

        /**
         * Demi-fenêtre d'évitement (m) : au-delà de 2 m de l'axe, l'obstacle
         * passe au large — l'ordre de s'écarter ne s'applique pas (ordre user
         * du 30/10).
         */
        const val AVOID_HALF_WIDTH_M = 2f

        /** Gauche/droite de l'axe : pile au centre = couloir, pas côté. */
        const val SIDE_MID = 0.5f

        /**
         * Sous cette distance (m), l'obstacle conseille quand même sans TTC ni
         * approche confirmée : compatibilité avec le comportement d'avant le
         * 01/11 (le calcul, lui, se fait sur la distance projetée).
         */
        const val CLOSE_METERS = 1.5f

        /** Diminutions consécutives pour confirmer (3e frame parle). */
        const val CONFIRM_DECREASES = 2

        /** Diminution minimale par frame (m) : sous le bruit de profondeur. */
        const val MIN_DECREASE_M = 0.05f

        /** Sans observation au-delà, la piste est périmée (miroir véhicules). */
        const val STALE_MS = 1000L

        /**
         * Tenue minimale d'un côté (ms) : l'audit a compté 88 changements de mot
         * en 120 s — un côté conseillé ne clignote plus frame à frame.
         */
        const val MIN_HOLD_MS = 1_500L

        /** Dégagement supplémentaire exigé pour changer de côté (m). */
        const val SWITCH_GAIN_M = 0.35f
    }
}
