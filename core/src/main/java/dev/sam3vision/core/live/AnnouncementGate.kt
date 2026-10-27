package dev.sam3vision.core.live

/**
 * Machine anti-répétition partagée des annonces vocales, calquée sur le flux du
 * direct : une à deux frames par seconde, des objets qui tremblent d'une frame à
 * l'autre.
 *
 * Trois garde-fous, dans cet ordre :
 * 1. **stabilité** — la même clé doit être vue [minFrames] fois de suite (une frame
 *    isolée, un masque qui clignote, ne déclenche rien) ;
 * 2. **nouveauté** — une clé déjà annoncée reste muette, même si la scène est
 *    toujours là ;
 * 3. **délai** — jamais deux annonces à moins de [cooldownMs] (le temps de dire la
 *    phrase précédente), sauf appel [urgent].
 *
 * Utilisée par [PositionAnnouncer] (positions) et [GuidanceAnnouncer] (consignes de
 * guidage, dont le danger parle tout de suite). Pure et testée en JVM (aucun
 * Android, aucune horloge implicite : [atMs] est fourni).
 */
class AnnouncementGate(
    private val minFrames: Int = DEFAULT_MIN_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    private var pendingKey: String = ""
    private var pendingCount: Int = 0
    private var spokenKey: String? = null
    private var lastSpokenAtMs: Long? = null

    /** Dernière phrase produite (`null` tant que rien n'a été annoncé). */
    var lastPhrase: String? = null
        private set

    /**
     * Clé vue à [atMs] : vrai s'il faut parler (stable, nouvelle, délai écoulé).
     * [urgent] saute le délai — le danger n'attend pas la fin de la phrase en cours.
     * L'appelant qui obtient vrai doit appeler [markSpoken] une fois la phrase dite ;
     * s'il ne dit rien (phrase vide), il n'appelle rien et la mémoire est inchangée.
     */
    fun admit(atMs: Long, key: String, urgent: Boolean = false): Boolean {
        advance(key)
        if (pendingCount < minFrames || key == spokenKey) return false
        val last = lastSpokenAtMs
        if (!urgent && last != null && atMs - last < cooldownMs) return false
        return true
    }

    /** Mémorise l'annonce dite : clé, instant et phrase. */
    fun markSpoken(key: String, atMs: Long, phrase: String) {
        spokenKey = key
        lastSpokenAtMs = atMs
        lastPhrase = phrase
    }

    /** Nouvelle session : tout est oublié (la première annonce sera dite). */
    fun reset() {
        pendingKey = ""
        pendingCount = 0
        spokenKey = null
        lastSpokenAtMs = null
        lastPhrase = null
    }

    /** Scène vide : la mémoire de scène retombe, pour que son retour parle. */
    fun clearScene() {
        pendingKey = ""
        pendingCount = 0
        spokenKey = null
    }

    /** Compteur de stabilité : la même clé deux frames de suite. */
    private fun advance(key: String) {
        if (key == pendingKey) {
            pendingCount += 1
        } else {
            pendingKey = key
            pendingCount = 1
        }
    }

    companion object {
        /** Clés consécutives identiques avant de parler (≈ 1 s à 2 ips). */
        const val DEFAULT_MIN_FRAMES = 2

        /** Délai minimal entre deux annonces : le temps de dire une phrase courte. */
        const val DEFAULT_COOLDOWN_MS = 4_000L
    }
}
