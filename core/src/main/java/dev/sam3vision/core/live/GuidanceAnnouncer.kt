package dev.sam3vision.core.live

import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Passage
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur des consignes de guidage : **la direction en continu, en moins
 * de 100 ms** (ordre user du 31/10 — [GuidanceWord], clips pré-synthétisés).
 *
 * La machine anti-répétition est [AnnouncementGate], partagée avec les
 * annonces de positions : ici, tout mot saute le délai **et la stabilité** —
 * une consigne nouvelle parle dès la première frame (seul « Stop. » coupe
 * la lecture en cours, les mots doux attendent le silence), et la même
 * direction se redit toutes les [DEFAULT_REPEAT_MS] millisecondes tant
 * qu'elle dure — sauf overwrite (un mot nouveau parle aussitôt, sans
 * attendre l'échéance). Seul le stop ne se répète jamais : parlé une fois,
 * il ne se rabâche pas.
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
     * la flèche latérale, en direct comme la décision.
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
     * millisecondes, sauf overwrite (mot nouveau aussitôt) ; stop unique.
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
    ): String? {
        val effective = routeCap ?: cap
        val decision = samPassage?.let { samDecision(it, labels, effective, hfovDeg) }
            ?: Guidance.decide(labels, effective, rangeMeters, hfovDeg)
        lastDecision = decision
        lastSidewalk = sidewalk
        lastNudge = null
        if (decision.blocked) {
            val tl = TwinLiteWord.fromGuidance(Guidance.word(decision))
            val again = if (tl == TwinLiteWord.Stop) null else repeatMs
            return speak(atMs, tl.key, tl.text(language), again)
        }
        val word = nudgeOf(atMs, labels, hfovDeg)?.let { GuidanceWord.fromCap(it.side) }
            ?: sidewalkWord(sidewalk, decision.cap)
        return speak(atMs, word.key, word.text(language), repeatMs)
    }

    /**
     * Décision lue dans le passage SAM : bouché et côté viennent du serveur
     * (les mêmes boîtes, jugées à pleine résolution), l'obstacle chiffré des
     * mesures du téléphone. Sans côté (contrat rompu), la gauche — comme
     * l'égalité côté serveur.
     */
    private fun samDecision(
        passage: Passage,
        labels: List<PlacedLabel>,
        cap: SteeringCap,
        hfovDeg: Float,
    ): GuidanceDecision {
        if (!passage.blocked) return GuidanceDecision(blocked = false, cap = cap)
        val side = if (passage.side == "right") GuidanceSide.Right else GuidanceSide.Left
        val exit = if (side == GuidanceSide.Right) SteeringCap.Right else SteeringCap.Left
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
            side = if (cap == SteeringCap.Straight) side else null,
            cap = cap,
            passage = exit,
            shiftMeters = Guidance.shiftOf(obstacle?.meters, half, hfovDeg),
        )
    }

    /** Décalage latéral : obstacle proche qui s'approche — mot du côté où aller. */
    private fun nudgeOf(atMs: Long, labels: List<PlacedLabel>, hfovDeg: Float): LateralNudge? {
        val cue = nudge.onFrame(atMs, labels, hfovDeg) ?: return null
        lastNudge = cue
        return cue
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
     * Mot SAM ou TwinLite : nouveau dit aussitôt (overwrite, sans délai ni
     * stabilité, clips < 100 ms), même direction redite après [again]
     * (`null` = stop unique, jamais répété).
     */
    private fun speak(atMs: Long, key: String, phrase: String, again: Long?): String? {
        if (!gate.admit(atMs, key, urgent = true, firstSight = true, repeatMs = again)) return null
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
