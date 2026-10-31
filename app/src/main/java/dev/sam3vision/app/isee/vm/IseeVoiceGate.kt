package dev.sam3vision.app.isee.vm

import android.os.SystemClock

/**
 * Porte de la voix ambiante d'iSee : l'ordre « désactive le GPS » la ferme,
 * les actions de l'utilisateur la rouvrent à la fenêtre.
 *
 * Contrat (demande produit : la voix IA ne parle « que si question, photo,
 * caméra, ou GPS activé ») :
 * - GPS actif : la porte est ouverte ([ambientAllowed]);
 * - ordre « désactive le GPS » ([muteGps]) : la voix ambiante — annonces de
 *   la sonde comme voix du sol TwinLite — se tait, jusqu'à réactivation
 *   (« active le GPS », Réglages, guidage relancé) ;
 * - une action de l'utilisateur ([openWindow] : caméra activée, vidéo ou
 *   photo prise) rouvre la parole pendant [AMBIENT_WINDOW_MS] : la fenêtre
 *   laisse la voix décrire ce qui vient d'être pris ;
 * - les urgences ne passent pas par le silence ([maySpeak]) : GPS coupé ou
 *   pas, voix occupée ou pas, elles parlent — la sécurité passe avant le
 *   silence demandé (miroir `SafetyVoice`, qui coupe tout, tout le temps).
 *
 * Non persistée : à chaque redémarrage la porte rouvre — un ordre de la
 * veille ne tait pas la voix du lendemain (l'appelant ne la resynchronise
 * pas avec le réglage persisté).
 *
 * Pur (l'horloge est injectée, testé en JVM sans Android).
 */
class IseeVoiceGate(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val windowMs: Long = AMBIENT_WINDOW_MS,
) {

    /** Ordre « désactive le GPS » en vigueur : la voix ambiante est fermée. */
    @Volatile
    private var gpsMuted = false

    /** Fin de la fenêtre de parole ouverte par une action. */
    @Volatile
    private var allowedUntil = 0L

    /** Consomme l'ordre « désactive le GPS » (idempotent). */
    fun muteGps() {
        gpsMuted = true
    }

    /** GPS actif de nouveau (« active le GPS », Réglages, guidage relancé). */
    fun unmuteGps() {
        gpsMuted = false
    }

    /** Une action de l'utilisateur (photo, caméra) rouvre la parole un instant. */
    fun openWindow() {
        allowedUntil = clock() + windowMs
    }

    /** Vrai quand la parole ambiante est permise : GPS actif, ou fenêtre ouverte. */
    fun ambientAllowed(): Boolean = !gpsMuted || clock() < allowedUntil

    /**
     * Décision d'une voix ambiante : l'urgence passe toujours (GPS coupé
     * comme occupé — la sécurité ne négocie pas) ; sinon, GPS actif → parler
     * seulement quand rien n'occupe la voix, GPS coupé → fenêtre ouverte
     * seulement (la désactivation reste une demande de silence pour le doux).
     */
    fun maySpeak(urgent: Boolean, busy: Boolean): Boolean = when {
        urgent -> true
        !gpsMuted -> !busy
        ambientAllowed() -> !busy
        else -> false
    }

    companion object {
        /**
         * Durée de la fenêtre laissée ouverte par une action (photo prise,
         * caméra activée) : une phrase de scène, pas un retour au bavardage.
         */
        const val AMBIENT_WINDOW_MS = 6_000L
    }
}
