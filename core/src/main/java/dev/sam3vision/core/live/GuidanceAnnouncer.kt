package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur des consignes de guidage, avec une différence assumée : **le danger
 * parle tout de suite**.
 *
 * La machine anti-répétition (stabilité, nouveauté, délai) est [AnnouncementGate],
 * partagée avec les annonces de positions : ici, tout passage au bouché saute le
 * délai — un obstacle qui entre dans le couloir ne doit pas attendre la fin de la
 * phrase en cours (le lecteur audio coupe : une seule lecture à la fois). Le retour
 * au libre, lui, attend : la réassurance peut patienter, le danger non.
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
     * Frame observée à [atMs] : renvoie la consigne à prononcer, ou `null` s'il faut
     * garder le silence. [language] est la langue de la voix (Réglages) : elle ne
     * change ni les seuils, ni la mémoire de décision. [cap] est la direction
     * demandée à la voix : il change le couloir marché, et sa signature fait
     * reparler dès que le cap change. [hfovDeg] calibre le décalage chiffré
     * (défaut 67° : l'appelant qui connaît la focale la passe).
     */
    fun onFrame(
        atMs: Long,
        labels: List<PlacedLabel>,
        language: SpeechLanguage = SpeechLanguage.Fr,
        cap: SteeringCap = SteeringCap.Straight,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
    ): String? {
        val decision = Guidance.decide(labels, cap, rangeMeters, hfovDeg)
        val key = Guidance.key(decision)
        if (!gate.admit(atMs, key, urgent = decision.blocked)) return null
        val phrase = Guidance.phrase(decision, language)
        gate.markSpoken(key, atMs, phrase)
        return phrase
    }

    /** Nouvelle session : tout est oublié (la première consigne sera dite). */
    fun reset() = gate.reset()
}
