package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur de l'annonce vocale des positions, calqué sur le flux du direct :
 * une à deux frames par seconde, des objets qui tremblent d'une frame à l'autre.
 *
 * Trois garde-fous, dans cet ordre :
 * 1. **stabilité** — la même scène doit être vue [minFrames] fois de suite (une frame
 *    isolée, un masque qui clignote, ne déclenche rien) ;
 * 2. **nouveauté** — une scène déjà annoncée ([PositionText.key]) reste muette, même
 *    si les objets sont toujours là ;
 * 3. **délai** — jamais deux annonces à moins de [cooldownMs] (le temps de dire la
 *    phrase précédente).
 *
 * Enfin, scène vide ⇒ l'objet a quitté le champ : la scène suivante sera annoncée.
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [atMs] est fourni).
 */
class PositionAnnouncer(
    private val minFrames: Int = DEFAULT_MIN_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxObjects: Int = PositionText.MAX_OBJECTS,
) {

    private var pendingKey: String = ""
    private var pendingCount: Int = 0
    private var spokenKey: String? = null
    private var lastSpokenAtMs: Long? = null

    /** Dernière phrase produite (`null` tant que rien n'a été annoncé). */
    var lastPhrase: String? = null
        private set

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
        if (key.isEmpty()) return onEmptyScene()
        advance(key)
        if (pendingCount < minFrames || key == spokenKey) return null
        val last = lastSpokenAtMs
        if (last != null && atMs - last < cooldownMs) return null
        val phrase = PositionText.phrase(labels, maxObjects, language) ?: return null
        spokenKey = key
        lastSpokenAtMs = atMs
        lastPhrase = phrase
        return phrase
    }

    /** Nouvelle session de direct : tout est oublié (la première scène sera annoncée). */
    fun reset() {
        pendingKey = ""
        pendingCount = 0
        spokenKey = null
        lastSpokenAtMs = null
        lastPhrase = null
    }

    /** Rien en vue : la mémoire de scène retombe, pour que le retour des objets parle. */
    private fun onEmptyScene(): String? {
        pendingKey = ""
        pendingCount = 0
        spokenKey = null
        return null
    }

    /** Compteur de stabilité : la même scène deux frames de suite. */
    private fun advance(key: String) {
        if (key == pendingKey) {
            pendingCount += 1
        } else {
            pendingKey = key
            pendingCount = 1
        }
    }

    companion object {
        /** Frames consécutives identiques avant de parler (≈ 1 s à 2 fps). */
        const val DEFAULT_MIN_FRAMES = 2

        /** Délai minimal entre deux annonces : le temps de dire une phrase courte. */
        const val DEFAULT_COOLDOWN_MS = 4_000L
    }
}