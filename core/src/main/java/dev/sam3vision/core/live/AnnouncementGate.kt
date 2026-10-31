package dev.sam3vision.core.live

/**
 * Machine anti-répétition partagée des annonces vocales, calquée sur le flux du
 * direct : une à deux frames par seconde, des objets qui tremblent d'une frame à
 * l'autre.
 *
 * Trois garde-fous, dans cet ordre :
 * 1. **stabilité** — la même clé doit être vue [minFrames] fois de suite (une frame
 *    isolée, un masque qui clignote, ne déclenche rien), sauf appel [firstSight] :
 *    le danger parle dès la première frame — à 2 ips, attendre la deuxième, c'est
 *    une demi-seconde de marche en plus, et un obstacle qui s'approche change de
 *    clé à chaque frame (seau de distance) donc ne serait jamais stable ;
 * 2. **nouveauté** — une clé déjà annoncée reste muette, même si la scène est
 *    toujours là, sauf [repeatMs] (répétition en continu des directions) ;
 * 3. **délai** — jamais deux annonces à moins de [cooldownMs] (le temps de dire la
 *    phrase précédente), sauf appel [urgent].
 *
 * Utilisée par [PositionAnnouncer] (positions, sans répétition) et
 * [GuidanceAnnouncer] (consignes : le danger parle tout de suite, les directions
 * douces se répètent). Pure et testée en JVM (aucun Android, aucune horloge
 * implicite : [atMs] est fourni).
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
     * [firstSight] saute la stabilité — une clé nouvelle parle dès la première
     * frame (la nouveauté reste : une clé déjà dite ne reparle pas, sauf [repeatMs]).
     * [repeatMs] redit la même clé après ce silence (`null` = jamais, défaut
     * historique) : les directions se répètent en continu, le stop ne se répète pas.
     * L'appelant qui obtient vrai doit appeler [markSpoken] une fois la phrase dite ;
     * s'il ne dit rien (phrase vide), il n'appelle rien et la mémoire est inchangée.
     */
    fun admit(
        atMs: Long,
        key: String,
        urgent: Boolean = false,
        firstSight: Boolean = false,
        repeatMs: Long? = null,
    ): Boolean {
        advance(key)
        if (key == spokenKey) return admitsRepeat(atMs, repeatMs)
        if (!firstSight && pendingCount < minFrames) return false
        val last = lastSpokenAtMs
        if (!urgent && last != null && atMs - last < cooldownMs) return false
        return true
    }

    /** Même clé que la dernière dite : vrai après [repeatMs] de silence, sinon faux. */
    private fun admitsRepeat(atMs: Long, repeatMs: Long?): Boolean {
        if (repeatMs == null) return false
        val last = lastSpokenAtMs ?: return false
        if (atMs < last) return false
        return atMs - last >= repeatMs
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

        /**
         * Délai minimal entre deux annonces (3 s) : le temps de dire une phrase
         * courte à ×1,9. Passé de 4 s à 3 s (audit voix 31/10) : à 1,2 m/s,
         * 4 s de silence valent 5 m de marche sans voix — trop pour un
         * obstacle qui s'approche ; 3 s gardent une annonce par pas sans
         * chevaucher la phrase précédente.
         */
        const val DEFAULT_COOLDOWN_MS = 3_000L
    }
}
