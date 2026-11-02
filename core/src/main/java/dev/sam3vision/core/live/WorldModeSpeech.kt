package dev.sam3vision.core.live

/**
 * Annonce vocale de la bascule dedans/dehors (quadruple tap sur l'accueil
 * iSee) : anglais imposé par le produit, quel que soit le réglage de langue.
 */
object WorldModeSpeech {
    /** Dit quand le quadruple tap active le dedans. */
    const val INDOOR = "indoor mode"

    /** Dit quand le quadruple tap active le dehors. */
    const val OUTDOOR = "outdoor mode"

    /** Annonce du monde activé ([outdoor] = dehors). */
    fun announcement(outdoor: Boolean): String = if (outdoor) OUTDOOR else INDOOR
}
