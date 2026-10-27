package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur des consignes de guidage, calqué sur [PositionAnnouncer] avec une
 * différence assumée : **le danger parle tout de suite**.
 *
 * Trois garde-fous, dans cet ordre :
 * 1. **stabilité** — la même décision [minFrames] fois de suite (un masque qui
 *    clignote ne fait pas changer de trottoir) ;
 * 2. **nouveauté** — une décision déjà dite ([Guidance.key]) reste muette ;
 * 3. **délai** — sauf urgence, jamais deux consignes à moins de [cooldownMs].
 *
 * L'urgence, c'est tout passage au bouché : un obstacle qui entre dans le couloir
 * ne doit pas attendre la fin du délai — le lecteur audio coupe la phrase en cours
 * (`SpeechPlayer` : une seule lecture à la fois). Le retour au libre, lui, attend :
 * la réassurance peut patienter, le danger non.
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [atMs] est fourni).
 */
class GuidanceAnnouncer(
    private val minFrames: Int = DEFAULT_MIN_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val rangeMeters: Float = Guidance.RANGE_METERS,
) {

    private var pendingKey: String = ""
    private var pendingCount: Int = 0
    private var spokenKey: String? = null
    private var lastSpokenAtMs: Long? = null

    /** Dernière consigne produite (`null` tant que rien n'a été dit). */
    var lastPhrase: String? = null
        private set

    /**
     * Frame observée à [atMs] : renvoie la consigne à prononcer, ou `null` s'il faut
     * garder le silence. [language] est la langue de la voix (Réglages) : elle ne
     * change ni les seuils, ni la mémoire de décision. [cap] est la direction
     * demandée à la voix : il change le couloir marché, et sa signature fait
     * reparler dès que le cap change.
     */
    fun onFrame(
        atMs: Long,
        labels: List<PlacedLabel>,
        language: SpeechLanguage = SpeechLanguage.Fr,
        cap: SteeringCap = SteeringCap.Straight,
    ): String? {
        val decision = Guidance.decide(labels, cap, rangeMeters)
        val key = Guidance.key(decision)
        advance(key)
        if (pendingCount < minFrames || key == spokenKey) return null
        val last = lastSpokenAtMs
        if (!decision.blocked && last != null && atMs - last < cooldownMs) return null
        val phrase = Guidance.phrase(decision, language)
        spokenKey = key
        lastSpokenAtMs = atMs
        lastPhrase = phrase
        return phrase
    }

    /** Nouvelle session : tout est oublié (la première consigne sera dite). */
    fun reset() {
        pendingKey = ""
        pendingCount = 0
        spokenKey = null
        lastSpokenAtMs = null
        lastPhrase = null
    }

    /** Compteur de stabilité : la même décision deux frames de suite. */
    private fun advance(key: String) {
        if (key == pendingKey) {
            pendingCount += 1
        } else {
            pendingKey = key
            pendingCount = 1
        }
    }

    companion object {
        /** Décisions consécutives identiques avant de parler (≈ 1 s à 2 ips). */
        const val DEFAULT_MIN_FRAMES = 2

        /** Délai minimal entre deux consignes non urgentes : le temps de dire la précédente. */
        const val DEFAULT_COOLDOWN_MS = 4_000L
    }
}
