package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes

/**
 * Étiquettes qui désignent une personne (FR/EN, canoniques) : le décalage
 * latéral, la voix des positions et le mode Traversée y lisent le même
 * vocabulaire — une seule liste, pas une par guetteur.
 */
object PersonLabels {

    private val KEYS = setOf(
        "person", "persons", "people", "pedestrian", "pedestrians",
        "child", "children", "personne", "personnes", "humain", "humains",
        "homme", "hommes", "femme", "femmes", "enfant", "enfants",
        "pieton", "pietons",
    )

    /** Vrai si [label] (brut, tel que renvoyé par SAM) désigne une personne. */
    fun isPerson(label: String): Boolean =
        KnownSizes.canonical(label) in KEYS
}
