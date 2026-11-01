package dev.sam3vision.core.live

import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Passage
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur des consignes de guidage : **la direction en continu, en moins
 * de 100 ms** (ordre user du 31/10 — [GuidanceWord], clips pré-synthétisés).
 *
 * Audit foule du 01/11/2026 — les trois chiffres qui ont créé ce patch : la
 * cadence des décisions est de **1,4-1,7 par seconde**, chaque décision juge
 * une image vieille de 0,65-0,70 s (soit **1,9 m d'erreur** à 1,4 m/s, une
 * largeur de trottoir), et la voix était **muette 90-94 % des frames**. D'où
 * trois règles, appliquées dans cet ordre :
 * - **on ne se tait plus en foule** : voie libre + décalage ([LateralNudge])
 *   ⇒ le mot du côté sort toujours ; décalage serré ([LateralNudge.tight]),
 *   la phrase reste ce mot de côté — le catalogue n'a pas de clip « serrez »,
 *   on n'en invente pas (aucun clip créé, [GuidanceWord] inchangé) ;
 * - **fraîcheur** ([Timing.isStale]) : une frame plus vieille que
 *   [Timing.STALE_MS] ne prouve pas que la voie est libre, donc jamais
 *   « Tout droit. » ; un côté (décalage, retour trottoir) et un stop restent
 *   valables, eux, et parlent ;
 * - **urgence hors cadence** ([Timing.isUrgent]) : un TTC sous
 *   [Timing.URGENT_TTC_S] fait sortir le mot du côté même si
 *   [AnnouncementGate] le retenait (redite récente) — on écrit par le chemin
 *   d'écriture existant ([AnnouncementGate.markSpoken]) et l'échéance de
 *   redite repart de cet instant.
 *
 * La machine anti-répétition est [AnnouncementGate], partagée avec les
 * annonces de positions : ici, tout mot saute le délai **et la stabilité** —
 * une consigne nouvelle parle dès la première frame (seul « Stop. » coupe
 * la lecture en cours, les mots doux attendent le silence), et la même
 * direction se redit toutes les [DEFAULT_REPEAT_MS] millisecondes tant
 * qu'elle dure — sauf overwrite (un mot nouveau parle aussitôt, sans
 * attendre l'échéance). Aucun stop n'est produit ici depuis le 31/10 (ordre
 * user : on se faufile, on ne s'arrête pas devant un obstacle) — le seul stop
 * qui reste est celui de la voix de sécurité, hors de cette classe.
 *
 * Arbitrage voix (ordre user du 29/10, stop du guidage retiré le 31/10) :
 * couloir bouché → voix TwinLite ([TwinLiteWord], côté de sortie même au
 * contact), voie libre → mots SAM ([GuidanceWord] : décalage, retour
 * trottoir, cap marché). Une seule porte pour les deux voix : un seul
 * parle à la fois, jamais de doublon, et le bouché prime toujours — la
 * sécurité ne négocie jamais.
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [atMs] est fourni).
 */
class GuidanceAnnouncer(
    minFrames: Int = AnnouncementGate.DEFAULT_MIN_FRAMES,
    cooldownMs: Long = AnnouncementGate.DEFAULT_COOLDOWN_MS,
    private val rangeMeters: Float = Guidance.RANGE_METERS,
    private val repeatMs: Long = DEFAULT_REPEAT_MS,
) {

    private val gate = AnnouncementGate(minFrames, cooldownMs)

    /** Dernière consigne produite (`null` tant que rien n'a été dit). */
    val lastPhrase: String? get() = gate.lastPhrase

    /**
     * Décision de la dernière frame (`null` avant la première) : la vidéo y
     * lit le chemin à dessiner, en direct même quand la voix reste muette.
     */
    var lastDecision: GuidanceDecision? = null
        private set

    /**
     * Trottoir de la dernière frame (`null` avant la première) : la vidéo y
     * lit le retour à dessiner, en direct comme la décision.
     */
    var lastSidewalk: SidewalkPosition? = null
        private set

    /**
     * Décalage de la dernière frame (`null` le plus souvent) : la vidéo y lit
     * la flèche latérale et la voix lit [LateralNudge.tight], en direct comme
     * la décision. Toujours cohérent avec la place libre de la frame.
     */
    var lastNudge: LateralNudge? = null
        private set

    private val nudge = LateralNudgeTracker()

    /**
     * Frame observée à [atMs] : renvoie la consigne à prononcer, ou `null` s'il faut
     * garder le silence. [language] est la langue de la voix (Réglages) : elle ne
     * change ni les seuils, ni la mémoire de décision. [cap] est la direction
     * demandée à la voix : il change le couloir marché, et sa signature fait
     * reparler dès que le cap change. [routeCap] (cap de l'itinéraire, guidage
     * route actif) prime sur [cap] quand il est renseigné. [hfovDeg] calibre le
     * décalage chiffré et la fenêtre d'évitement (défaut 67° : l'appelant qui
     * connaît la focale la passe).
     * [sidewalk] est le trottoir vu caméra : sur voie libre, un côté suspend le
     * cap (ordre de retour), une absence parle prudemment ; le bouché prime.
     * [samPassage] est le passage conseillé par SAM : bouché et côté du
     * serveur, obstacle chiffré du téléphone ; `null` (backend antérieur) =
     * décision locale. Direction stable : redite toutes les [repeatMs]
     * millisecondes, sauf overwrite (mot nouveau aussitôt) ; jamais de stop.
     * [ageMs] est l'âge de la mesure (ms) : vieille ([Timing.isStale]),
     * elle ne dit plus « Tout droit. ». [speedMps] est la vitesse de marche
     * (GPS) : la portée suit l'horizon ([Timing.rangeFor]), donc on voit
     * venir de plus loin en marchant vite ; `null` = portée historique.
     * [free] est la place libre de part et d'autre de l'axe : `null` la fait
     * estimer ici ([FreeSpace.of]) — elle sert à marquer un décalage serré
     * ([LateralNudge.tight]) que la voix ne peut pas promettre. Les trois
     * ont une valeur par défaut : aucun appelant existant ne change.
     */
    fun onFrame(
        atMs: Long,
        labels: List<PlacedLabel>,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        cap: SteeringCap = SteeringCap.Straight,
        routeCap: SteeringCap? = null,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        sidewalk: SidewalkPosition = SidewalkPosition.Ahead,
        samPassage: Passage? = null,
        ageMs: Long = 0L,
        speedMps: Float? = null,
        free: FreeSpace? = null,
    ): String? {
        val effective = routeCap ?: cap
        val space = free ?: FreeSpace.of(labels, hfovDeg)
        val range = rangeFor(speedMps)
        val decision = samPassage?.let { samDecision(it, labels, effective, hfovDeg, range) }
            ?: Guidance.decide(labels, effective, range, hfovDeg)
        lastDecision = decision
        lastSidewalk = sidewalk
        lastNudge = null
        // L'évitement passe DEVANT la décision de couloir : en foule le couloir
        // est bouché presque en permanence, et c'est justement là que l'ordre
        // doit venir de la piste (TTC, hystérésis, espace libre) et non du
        // « côté le moins encombré » d'une frame. Un obstacle pile dans l'axe
        // n'a pas de côté : le couloir parle alors seul.
        val cue = nudgeOf(atMs, labels, hfovDeg, ageMs, speedMps, space)
        if (cue != null) return nudgeWord(atMs, cue, language)
        if (decision.blocked) return blockedWord(atMs, decision, language)
        val word = clearedReturnWord(sidewalk, decision.cap, labels, hfovDeg, range)
        // Frame périmée : elle ne prouve pas que la voie est libre (« Tout
        // droit. » est une affirmation, pas une absence) ; un côté reste bon.
        if (word == GuidanceWord.Straight && Timing.isStale(ageMs)) return null
        return sideWord(atMs, word, language)
    }

    /**
     * Couloir bouché : le côté de sortie TwinLite, dit dès la première frame
     * (`again = null` pour un stop, qui ne se répète jamais). Le bouché prime
     * sur le décalage : `lastNudge` reste `null` (une seule flèche à l'écran).
     */
    private fun blockedWord(atMs: Long, decision: GuidanceDecision, language: SpeechLanguage): String? {
        val twin = TwinLiteWord.fromGuidance(Guidance.word(decision))
        val again = if (twin == TwinLiteWord.Stop) null else repeatMs
        return speak(atMs, directionKey(twin.text(language)), twin.text(language), again)
    }

    /**
     * Décalage latéral : le mot du côté où aller, **toujours** (voie libre
     * mais foule : on ne se tait plus). Urgent ([Timing.isUrgent]), il sort
     * même si la redite en cours le retenait. Serré ([LateralNudge.tight]),
     * la phrase reste le mot de côté — le catalogue n'a pas de clip pour
     * « serrez », la couche voix lit le drapeau.
     */
    private fun nudgeWord(atMs: Long, cue: LateralNudge, language: SpeechLanguage): String? {
        // Même vocabulaire que le couloir bouché (TwinLite) : une seule clé
        // d'anti-répétition pour une seule consigne de direction, sinon le
        // décalage et le couloir se disent deux fois le même côté.
        val word = GuidanceWord.fromCap(cue.side)
        return speak(atMs, directionKey(word.text(language)), word.text(language), repeatMs,
            urgent = Timing.isUrgent(cue.ttcS))
    }

    /**
     * Clé d'anti-répétition **de la direction**, indépendante du vocabulaire :
     * le décalage parle avec les mots SAM (`Left.`) et le couloir bouché avec
     * les mots TwinLite (`left.`) — même consigne, deux canaux. Sans clé
     * commune, les deux voix redisent le même côté à 0,7 s d'écart (« 88
     * changements de mot en 120 s », audit foule du 01/11).
     */
    private fun directionKey(text: String): String = "dir|" + text.lowercase().trimEnd('.')

    /** Mot de voie libre (cap, retour trottoir) : redit à la cadence normale. */
    private fun sideWord(atMs: Long, word: GuidanceWord, language: SpeechLanguage): String? =
        speak(atMs, directionKey(word.text(language)), word.text(language), repeatMs)

    /**
     * Décision lue dans le passage SAM : bouché et côté viennent du serveur
     * (les mêmes boîtes, jugées à pleine résolution), l'obstacle chiffré des
     * mesures du téléphone. Sans côté (contrat rompu), la gauche — comme
     * l'égalité côté serveur. Veto local 80 cm : si le côté serveur ne
     * garantit pas 80 cm à une personne et que l'autre côté les garantit,
     * on bascule (la sécurité locale prime sur le juge lointain).
     */
    private fun samDecision(
        passage: Passage,
        labels: List<PlacedLabel>,
        cap: SteeringCap,
        hfovDeg: Float,
        range: Float,
    ): GuidanceDecision {
        if (!passage.blocked) return GuidanceDecision(blocked = false, cap = cap)
        val side = if (passage.side == "right") GuidanceSide.Right else GuidanceSide.Left
        val other = if (side == GuidanceSide.Right) GuidanceSide.Left else GuidanceSide.Right
        val use = if (!PeoplePath.clearsSide(labels, side, range, hfovDeg) &&
            PeoplePath.clearsSide(labels, other, range, hfovDeg)
        ) {
            other
        } else {
            side
        }
        val exit = if (use == GuidanceSide.Right) SteeringCap.Right else SteeringCap.Left
        val obstacle = labels
            .mapNotNull { label -> label.meters?.takeIf { it.isFinite() }?.let { label to it } }
            .minByOrNull { it.second }?.first
        val half = if (cap == SteeringCap.Straight) {
            Guidance.STRAIGHT_HALF_CORRIDOR
        } else {
            Guidance.LATERAL_HALF_CORRIDOR
        }
        return GuidanceDecision(
            blocked = true,
            obstacle = obstacle,
            side = if (cap == SteeringCap.Straight) use else null,
            cap = cap,
            passage = exit,
            shiftMeters = Guidance.shiftOf(obstacle?.meters, half, hfovDeg),
        )
    }

    /**
     * Portée de la frame : l'horizon de marche ([Timing.rangeFor]) quand la
     * vitesse est connue — on voit venir de plus loin en marchant vite — mais
     * jamais moins que la portée historique ([rangeMeters]), pour qu'aucun
     * appelant sans GPS ne perde de champ.
     */
    private fun rangeFor(speedMps: Float?): Float =
        if (speedMps == null) rangeMeters else maxOf(rangeMeters, Timing.rangeFor(speedMps))

    /**
     * Décalage latéral : obstacle proche qui s'approche — mot du côté où aller.
     * [free] est la place libre de la frame : le drapeau [LateralNudge.tight]
     * est re-vérifié ici (`canShift`), pour que la couche voix ne reçoive
     * jamais un décalage « serré » contredit par le terrain.
     */
    private fun nudgeOf(
        atMs: Long,
        labels: List<PlacedLabel>,
        hfovDeg: Float,
        ageMs: Long,
        speedMps: Float?,
        free: FreeSpace,
    ): LateralNudge? {
        val cue = nudge.onFrame(
            atMs = atMs,
            labels = labels,
            hfovDeg = hfovDeg,
            ageMs = ageMs,
            speedMps = speedMps,
            free = free,
        ) ?: return null
        val tightened = tightOf(cue, free)
        lastNudge = tightened
        return tightened
    }

    /**
     * Décalage marqué serré si la place libre du côté conseillé n'a même pas
     * la marge de bord ([FreeSpace.EDGE_MARGIN_M]) : le mot reste celui du
     * côté, mais la couche voix sait qu'il n'y a pas de pas à promettre.
     * La règle est celle du tracker, re-vérifiée sur l'espace de la frame.
     */
    private fun tightOf(cue: LateralNudge, free: FreeSpace): LateralNudge {
        if (cue.tight) return cue
        val side = when (cue.side) {
            SteeringCap.Left -> GuidanceSide.Left
            SteeringCap.Right -> GuidanceSide.Right
            SteeringCap.Straight -> return cue
        }
        return if (free.canShift(side, FreeSpace.EDGE_MARGIN_M)) cue else cue.copy(tight = true)
    }

    /**
     * Mot du trottoir : le côté vu (ordre de retour), sinon le cap marché
     * (dessus ou introuvable : sans dévier — la prudence n'a pas de direction).
     */
    private fun sidewalkWord(sidewalk: SidewalkPosition, cap: SteeringCap): GuidanceWord = when (sidewalk) {
        SidewalkPosition.Left -> GuidanceWord.Left
        SidewalkPosition.Right -> GuidanceWord.Right
        else -> GuidanceWord.fromCap(cap)
    }

    /**
     * Ordre de retour trottoir garanti 80 cm : si le retour va vers une
     * personne à moins de 80 cm, on marche le cap au lieu de revenir vers
     * elle (le cap lui-même n'est jamais corrigé ici — le couloir s'en
     * charge quand il est bouché).
     */
    private fun clearedReturnWord(
        sidewalk: SidewalkPosition,
        cap: SteeringCap,
        labels: List<PlacedLabel>,
        hfovDeg: Float,
        range: Float,
    ): GuidanceWord {
        val word = sidewalkWord(sidewalk, cap)
        val capWord = GuidanceWord.fromCap(cap)
        if (word == capWord) return word
        val side = if (word == GuidanceWord.Left) GuidanceSide.Left else GuidanceSide.Right
        return if (PeoplePath.clearsSide(labels, side, range, hfovDeg)) word else capWord
    }

    /**
     * Mot SAM ou TwinLite : nouveau dit aussitôt (overwrite, sans délai ni
     * stabilité, clips < 100 ms), même direction redite après [again]
     * (`null` = jamais répété ; plus aucun stop depuis le 31/10).
     * [urgent] est le seul chemin qui ignore la redite : on écrit alors
     * directement la mémoire ([AnnouncementGate.markSpoken], le même chemin
     * d'écriture que les autres mots) et la phrase sort, pour un contact
     * sous [Timing.URGENT_TTC_S] — la cadence ne retarde jamais un danger.
     */
    private fun speak(
        atMs: Long,
        key: String,
        phrase: String,
        again: Long?,
        urgent: Boolean = false,
    ): String? {
        if (!urgent && !gate.admit(atMs, key, urgent = true, firstSight = true, repeatMs = again)) return null
        gate.markSpoken(key, atMs, phrase)
        return phrase
    }

    /** Nouvelle session : tout est oublié (la première consigne sera dite). */
    fun reset() {
        gate.reset()
        lastDecision = null
        lastSidewalk = null
        lastNudge = null
        nudge.reset()
    }

    companion object {
        /**
         * Direction stable redite toutes les 2 s (~2,4 m à 1,2 m/s) : comme le
         * rouge répété (`CrossingAnnouncer.RED_REPEAT_MS`), pour guider sans
         * bégayer. Injecté en test via [GuidanceAnnouncer.repeatMs].
         */
        const val DEFAULT_REPEAT_MS = 2_000L
    }
}
