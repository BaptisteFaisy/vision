package dev.sam3vision.app

/**
 * Arbitre unique du microphone. Deux fonctions de l'app ouvrent le micro et **ne peuvent pas
 * coexister** (un seul `AudioRecord` à la fois sur l'appareil) :
 *
 * - la dictée par appui long (`DictationFeature`, PCM 16 kHz) ;
 * - la piste audio de la vidéo du mode tap (`TapVideoSession`, `withAudioEnabled`).
 *
 * Sans arbitre, le second demandeur reçoit un micro muet : la dictée enregistre du silence,
 * ou la vidéo part sans son — deux pannes silencieuses. Ici, le second demandeur est
 * **refusé**, et l'appelant le dit ( bandeau, ou annonce `Failed` dans le mode tap ).
 *
 * Objet purement JVM (aucune dépendance Android) : testable, et partageable entre les deux
 * fonctions sans dupliquer la règle.
 */
object MicBus {

    /** Preneur possible du micro. `NONE` = personne. */
    enum class Owner { NONE, DICTATION, TAP_VIDEO }

    @Volatile
    var owner: Owner = Owner.NONE
        private set

    /** Vrai si [by] peut ouvrir le micro (libre, ou déjà tenu par lui-même). */
    fun acquire(by: Owner): Boolean = synchronized(this) {
        owner == Owner.NONE || owner == by
    }.also { ok -> if (ok) owner = by }

    /** Rend le micro : ignoré si c'est un autre preneur qui le tient (pas de vol). */
    fun release(by: Owner) {
        synchronized(this) { if (owner == by) owner = Owner.NONE }
    }

    /** Vrai quand le micro est tenu par quelqu'un d'autre que [by]. */
    fun busyFor(by: Owner): Boolean = synchronized(this) { owner != Owner.NONE && owner != by }

    /** Relâche tout : réinitialisation de session, arrêt d'activité. */
    fun reset() {
        synchronized(this) { owner = Owner.NONE }
    }
}
