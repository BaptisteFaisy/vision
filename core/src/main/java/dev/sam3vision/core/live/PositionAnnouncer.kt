package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur de l'annonce vocale des positions, calqué sur le flux du direct :
 * une à deux frames par seconde, des objets qui tremblent d'une frame à l'autre.
 *
 * La machine anti-répétition (stabilité, nouveauté, délai) est [AnnouncementGate],
 * partagée avec les consignes de guidage : ici, pas d'urgence — une scène vide fait
 * retomber la mémoire, pour que le retour des objets parle. Une scène nouvelle parle
 * dès la première frame (pas de stabilité : un objet qui s'approche change de clé
 * à chaque frame) ; le délai de 4 s reste, qui borne le bavardage.
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [atMs] est fourni).
 */
class PositionAnnouncer(
    minFrames: Int = AnnouncementGate.DEFAULT_MIN_FRAMES,
    cooldownMs: Long = AnnouncementGate.DEFAULT_COOLDOWN_MS,
    private val maxObjects: Int = PositionText.MAX_OBJECTS,
) {

    private val gate = AnnouncementGate(minFrames, cooldownMs)

    /** Dernière phrase produite (`null` tant que rien n'a été annoncé). */
    val lastPhrase: String? get() = gate.lastPhrase

    /**
     * Frame observée à [atMs] : renvoie la phrase à prononcer, ou `null` s'il faut
     * garder le silence. [language] est la langue de la voix (Réglages) : elle ne
     * change ni les seuils, ni la mémoire de scène.
     */
    fun onFrame(
        atMs: Long,
        labels: List<PlacedLabel>,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String? {
        val key = PositionText.key(labels)
        if (key.isEmpty()) {
            gate.clearScene()
            return null
        }
        if (!gate.admit(atMs, key, firstSight = true)) return null
        val phrase = PositionText.phrase(labels, maxObjects, language) ?: return null
        gate.markSpoken(key, atMs, phrase)
        return phrase
    }

    /** Nouvelle session de direct : tout est oublié (la première scène sera annoncée). */
    fun reset() = gate.reset()
}
