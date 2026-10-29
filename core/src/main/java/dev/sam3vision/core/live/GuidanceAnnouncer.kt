package dev.sam3vision.core.live

import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur des consignes de guidage : **un mot par changement, en moins de
 * 100 ms** (ordre user du 29/10 — [GuidanceWord], clips pré-synthétisés).
 *
 * La machine anti-répétition est [AnnouncementGate], partagée avec les
 * annonces de positions : ici, tout mot saute le délai **et la stabilité** —
 * une consigne nouvelle parle dès la première frame (seul « Stop. » coupe
 * la lecture en cours, les mots doux attendent le silence), et le même mot
 * ne se répète jamais (scène stable = silence, pas de bégaiement).
 *
 * Priorité du mot : stop (contact) > côté de sortie (bouché) > décalage
 * (personne qui s'approche par un côté) > retour trottoir (côté vu, sinon
 * cap) > cap marché. Le bouché prime toujours : la sécurité ne négocie jamais.
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [atMs] est fourni).
 */
class GuidanceAnnouncer(
    minFrames: Int = AnnouncementGate.DEFAULT_MIN_FRAMES,
    cooldownMs: Long = AnnouncementGate.DEFAULT_COOLDOWN_MS,
    private val rangeMeters: Float = Guidance.RANGE_METERS,
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
     * décalage chiffré (défaut 67° : l'appelant qui connaît la focale la passe).
     * [sidewalk] est le trottoir vu caméra : sur voie libre, un côté suspend le
     * cap (ordre de retour), une absence parle prudemment ; le bouché prime.
     */
    fun onFrame(
        atMs: Long,
        labels: List<PlacedLabel>,
        language: SpeechLanguage = SpeechLanguage.Fr,
        cap: SteeringCap = SteeringCap.Straight,
        routeCap: SteeringCap? = null,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        sidewalk: SidewalkPosition = SidewalkPosition.Ahead,
    ): String? {
        val effective = routeCap ?: cap
        val decision = Guidance.decide(labels, effective, rangeMeters, hfovDeg)
        lastDecision = decision
        lastSidewalk = sidewalk
        lastNudge = null
        val word = when {
            decision.blocked -> Guidance.word(decision)
            else -> nudgeOf(atMs, labels)?.let { GuidanceWord.fromCap(it.side) }
                ?: sidewalkWord(sidewalk, decision.cap)
        }
        return speakWord(atMs, word, language)
    }

    /** Décalage latéral : personne proche qui s'approche — mot du côté où aller. */
    private fun nudgeOf(atMs: Long, labels: List<PlacedLabel>): LateralNudge? {
        val cue = nudge.onFrame(atMs, labels) ?: return null
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

    /** Mot nouveau : dit aussitôt, sans délai ni stabilité (clips < 100 ms). */
    private fun speakWord(atMs: Long, word: GuidanceWord, language: SpeechLanguage): String? {
        if (!gate.admit(atMs, word.key, urgent = true, firstSight = true)) return null
        val phrase = word.text(language)
        gate.markSpoken(word.key, atMs, phrase)
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
}
