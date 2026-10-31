package dev.sam3vision.core.live

import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Passage
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur des consignes de guidage : **un mot par changement, en moins de
 * 100 ms** (ordre user du 29/10 — [GuidanceWord], clips pré-synthétisés).
 *
 * La machine anti-répétition est [AnnouncementGate], partagée avec les
 * annonces de positions : ici, tout mot saute le délai **et la stabilité** —
 * une consigne nouvelle parle dès la première frame (seul « Stop. » coupe
 * la lecture en cours, les mots doux attendent le silence). Sur voie libre
 * stable, le rappel de suivi ([ContinueWord], en alternance avec le mot)
 * reparle toutes les [DEFAULT_IDLE_REPEAT_MS] millisecondes : quand iSee n'a
 * rien de neuf à dire, il redit comment suivre le chemin au lieu de se
 * taire. Le bouché, lui, ne se répète jamais (scène bouchée stable =
 * silence, pas de bégaiement) : un ordre d'évitement ne se rabâche pas.
 *
 * Arbitrage voix (ordre user du 29/10) : couloir bouché → voix TwinLite
 * ([TwinLiteWord] : arrêt au contact, côté de sortie sinon), voie libre →
 * mots SAM ([GuidanceWord] : décalage, retour trottoir, cap marché). Une
 * seule porte pour les deux voix : un seul parle à la fois, jamais de
 * doublon, et le bouché prime toujours — la sécurité ne négocie jamais.
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [atMs] est fourni).
 */
class GuidanceAnnouncer(
    minFrames: Int = AnnouncementGate.DEFAULT_MIN_FRAMES,
    cooldownMs: Long = AnnouncementGate.DEFAULT_COOLDOWN_MS,
    private val rangeMeters: Float = Guidance.RANGE_METERS,
    private val idleRepeatMs: Long = DEFAULT_IDLE_REPEAT_MS,
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

    /** Clé du dernier mot de voie libre parlé (`null` tant qu'aucun). */
    private var lastFreeKey: String? = null

    /** Instant de la dernière émission de voie libre ([NO_FREE] si aucune). */
    private var lastFreeAtMs: Long = NO_FREE

    /** Vrai si le prochain rappel est le « Continue… » (faux : le mot). */
    private var continueDue: Boolean = true

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
     * décision locale. Sur voie libre stable, le rappel de suivi
     * ([ContinueWord]) reparle toutes les [idleRepeatMs] millisecondes.
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
            return speak(atMs, tl.key, tl.text(language))
        }
        val word = nudgeOf(atMs, labels, hfovDeg)?.let { GuidanceWord.fromCap(it.side) }
            ?: sidewalkWord(sidewalk, decision.cap)
        val phrase = speak(atMs, word.key, word.text(language))
        if (phrase != null) {
            lastFreeKey = word.key
            lastFreeAtMs = atMs
            continueDue = true
            return phrase
        }
        return idleFollow(atMs, word, language)
    }

    /**
     * Décision lue dans le passage SAM : bouché et côté viennent du serveur
     * (les mêmes boîtes, jugées à pleine résolution), l'obstacle chiffré des
     * mesures du téléphone (le mot stop a besoin d'une distance). Sans côté
     * (contrat rompu), la gauche — comme l'égalité côté serveur.
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
     * Rappel de suivi sur voie libre stable : [word] est la consigne déjà
     * dite (le `speak` vient d'échouer dessus) — après [idleRepeatMs] de
     * silence, le rappel ([ContinueWord]) puis le mot, en alternance : chaque
     * émission reste distincte de la précédente, là où les déduplications
     * aval (`LiveProbeState.spoken`, `ProximityVoice.lastPhrase`) avaleraient
     * une répétition à l'identique. La clé marquée reste celle du mot : un
     * vrai changement reparle aussitôt, comme avant. `null` hors délai,
     * consigne changée entre-temps, ou mot sans rappel (stop).
     */
    private fun idleFollow(atMs: Long, word: GuidanceWord, language: SpeechLanguage): String? {
        if (word.key != lastFreeKey || lastFreeAtMs == NO_FREE) return null
        if (atMs < lastFreeAtMs || atMs - lastFreeAtMs < idleRepeatMs) return null
        val follow = ContinueWord.fromGuidance(word) ?: return null
        val phrase = if (continueDue) follow.text(language) else word.text(language)
        continueDue = !continueDue
        lastFreeAtMs = atMs
        gate.markSpoken(word.key, atMs, phrase)
        return phrase
    }

    /** Mot nouveau, SAM ou TwinLite : dit aussitôt, sans délai ni stabilité (clips < 100 ms). */
    private fun speak(atMs: Long, key: String, phrase: String): String? {
        if (!gate.admit(atMs, key, urgent = true, firstSight = true)) return null
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
        lastFreeKey = null
        lastFreeAtMs = NO_FREE
        continueDue = true
    }

    companion object {
        /**
         * Silence de voie libre stable avant le rappel de suivi (8 s, soit
         * ~10 m à 1,2 m/s) : le double du délai d'annonce, pour rappeler sans
         * bégayer. Injecté en test via [GuidanceAnnouncer.idleRepeatMs].
         */
        const val DEFAULT_IDLE_REPEAT_MS = 8_000L

        /** Aucune émission de voie libre encore. */
        private const val NO_FREE = Long.MIN_VALUE
    }
}
