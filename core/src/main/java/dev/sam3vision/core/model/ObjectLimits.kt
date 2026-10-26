package dev.sam3vision.core.model

/**
 * Bornes partagées par les deux modes d'affichage (photo et direct).
 *
 * Une seule source de vérité : le plafond est appliqué côté client (photo, après
 * `/segment`) et transmis au serveur (direct, champ `max_objects` de `/live`),
 * qui élague en plus les scores les plus faibles. La valeur est réglable dans
 * Réglages ; elle est bornée à [[MIN_ON_SCREEN], [MAX_ON_SCREEN]] avant usage.
 */
object ObjectLimits {
    /**
     * Valeur par défaut : **8 objets** à l'écran (demande user du 26/10, confirmée le
     * 26/10 soir : « j'avais dit 8 »). Réglable de 1 à 12 dans Réglages.
     */
    const val DEFAULT_ON_SCREEN = 8
    const val MIN_ON_SCREEN = 1
    const val MAX_ON_SCREEN = 12

    /** Ramène un réglage hors bornes (saisie, vieux fichier) dans l'intervalle. */
    fun clamp(value: Int): Int = value.coerceIn(MIN_ON_SCREEN, MAX_ON_SCREEN)

    /** Pas de réglage − / + : sature aux bornes au lieu de déborder. */
    fun step(value: Int, delta: Int): Int = clamp(value + delta)
}
