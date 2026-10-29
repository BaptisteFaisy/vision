package dev.sam3vision.core.live

import dev.sam3vision.core.curb.SidewalkKeep
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.curb.SidewalkText
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur des consignes de guidage, avec une différence assumée : **le danger
 * parle tout de suite**.
 *
 * La machine anti-répétition (stabilité, nouveauté, délai) est [AnnouncementGate],
 * partagée avec les annonces de positions : ici, tout passage au bouché saute le
 * délai **et la stabilité** — un obstacle qui entre dans le couloir parle dès la
 * première frame (le lecteur audio coupe : une seule lecture à la fois), et un
 * obstacle qui s'approche reparle à chaque seau de distance au lieu de se taire
 * (sa clé change à chaque frame, elle ne serait jamais stable). Le retour au
 * libre, lui, attend : la réassurance peut patienter, le danger non.
 *
 * Maintien trottoir ([sidewalk]) : sur voie libre, un trottoir sur un côté suspend
 * la consigne de cap (« Remonte sur le trottoir, à gauche. ») jusqu'au retour
 * dessus, où le cap reprend ; un trottoir absent parle prudemment, sans dévier.
 * Le bouché prime toujours : la sécurité ne négocie jamais.
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
        if (decision.blocked) return speakBlocked(atMs, decision, language)
        nudgeOf(atMs, labels)?.let { return speakNudge(atMs, it, language) }
        val correction = SidewalkText.phrase(sidewalk, language)
        if (correction != null) return speakSidewalk(atMs, sidewalk, correction)
        return speakFree(atMs, decision, language)
    }

    /** Consigne d'obstacle : urgente, elle saute le délai (le danger n'attend pas). */
    private fun speakBlocked(atMs: Long, decision: GuidanceDecision, language: SpeechLanguage): String? {
        val key = Guidance.key(decision)
        if (!gate.admit(atMs, key, urgent = true, firstSight = true)) return null
        val phrase = Guidance.phrase(decision, language)
        gate.markSpoken(key, atMs, phrase)
        return phrase
    }

    /** Décalage latéral : personne proche qui s'approche — urgent comme le bouché. */
    private fun nudgeOf(atMs: Long, labels: List<PlacedLabel>): LateralNudge? {
        val cue = nudge.onFrame(atMs, labels) ?: return null
        lastNudge = cue
        return cue
    }

    /** Consigne de décalage : urgente, clé au mètre (reparle en se rapprochant). */
    private fun speakNudge(atMs: Long, cue: LateralNudge, language: SpeechLanguage): String? {
        val key = LateralNudge.key(cue)
        if (!gate.admit(atMs, key, urgent = true, firstSight = true)) return null
        val phrase = LateralNudgeText.phrase(cue.side, language)
        gate.markSpoken(key, atMs, phrase)
        return phrase
    }

    /** Correction trottoir : consigne, pas danger — elle attend son tour. */
    private fun speakSidewalk(atMs: Long, sidewalk: SidewalkPosition, correction: String): String? {
        val key = SidewalkKeep.key(sidewalk)
        if (!gate.admit(atMs, key, urgent = false)) return null
        gate.markSpoken(key, atMs, correction)
        return correction
    }

    /** Consigne de cap : la voie libre dans la direction demandée. */
    private fun speakFree(atMs: Long, decision: GuidanceDecision, language: SpeechLanguage): String? {
        val key = Guidance.key(decision)
        if (!gate.admit(atMs, key, urgent = false)) return null
        val phrase = Guidance.phrase(decision, language)
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
}
