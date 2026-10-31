package dev.sam3vision.core.model

/**
 * Bornes partagées par les deux modes d'affichage (photo et direct).
 *
 * Une seule source de vérité : le plafond est appliqué côté client (photo, après
 * `/segment`) et transmis au serveur (direct, champ `max_objects` de `/live`),
 * qui élague en plus les scores les plus faibles. La valeur est réglable dans
 * Réglages ; elle est bornée à [[MIN_ON_SCREEN], [MAX_ON_SCREEN]] avant usage.
 *
 * Chaîne du direct : `Settings.maxObjects` ([DEFAULT_ON_SCREEN] par défaut) est
 * envoyé tel quel en `max_objects`, borné serveur à 1..32 (16 si absent). Dans
 * ce plafond, le serveur réserve [GUARDED_SLOTS] places (feu, passage piéton) :
 * un plafond client sous cette réserve ne protège plus rien — le tri pur
 * s'applique, sans erreur.
 */
object ObjectLimits {
    /**
     * Valeur par défaut : **8 objets** à l'écran (demande user du 26/10, confirmée le
     * 26/10 soir : « j'avais dit 8 »). Réglable de 1 à 32 dans Réglages
     * (plafond 32 : demande user du 29/10).
     */
    const val DEFAULT_ON_SCREEN = 8
    const val MIN_ON_SCREEN = 1
    const val MAX_ON_SCREEN = 32

    /**
     * Places réservées par le serveur dans le plafond `/live` : une pour le
     * meilleur feu, une pour le meilleur passage piéton (`feux_guard`, slot
     * réservé — seuils inchangés). Miroir documentaire de la garde serveur.
     */
    const val GUARDED_SLOTS = 2

    /** Ramène un réglage hors bornes (saisie, vieux fichier) dans l'intervalle. */
    fun clamp(value: Int): Int = value.coerceIn(MIN_ON_SCREEN, MAX_ON_SCREEN)

    /** Pas de réglage − / + : sature aux bornes au lieu de déborder. */
    fun step(value: Int, delta: Int): Int = clamp(value + delta)

    /**
     * Vrai si [value] (réglage brut) laisse la garde serveur entière : le
     * plafond couvre les [GUARDED_SLOTS] réservés et au moins un objet
     * général — en dessous, le serveur trie sans réserver.
     */
    fun keepsGuarded(value: Int): Boolean = clamp(value) > GUARDED_SLOTS
}
