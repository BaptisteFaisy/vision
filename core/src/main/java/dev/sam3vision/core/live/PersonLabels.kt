package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes

/**
 * Étiquettes qui désignent un corps (FR/EN, canoniques) : le décalage
 * latéral, la voix des positions et le mode Traversée y lisent le même
 * vocabulaire — une seule liste, pas une par guetteur.
 *
 * Deux niveaux (audit foule du 01/11) : [isPerson] reste **strict** (les
 * phrases « à la personne » ne doivent pas dire « à la poussette »), alors
 * que [isBody] ajoute ce qui bloque sans être debout — un corps qui bloque
 * n'est pas toujours une personne debout.
 */
object PersonLabels {

    private val KEYS = setOf(
        "person", "persons", "people", "pedestrian", "pedestrians",
        "child", "children", "personne", "personnes", "humain", "humains",
        "human", "humans",
        "homme", "hommes", "femme", "femmes", "enfant", "enfants",
        "pieton", "pietons",
    )

    /**
     * Corps qui bloquent sans être une personne debout : poussette, fauteuil
     * roulant (canoniques FR/EN — trottinette sortie, spec v4 : plus
     * détectée). Un enfant n'est pas ici : il reste une personne ([KEYS]),
     * sa phrase se dit « à l'enfant ».
     */
    private val BODY_KEYS = setOf(
        "stroller", "poussette", "pram", "buggy",
        "wheelchair", "fauteuil roulant",
    )

    /** Vrai si [label] (brut, tel que renvoyé par SAM) désigne une personne. */
    fun isPerson(label: String): Boolean =
        KnownSizes.canonical(label) in KEYS

    /**
     * Vrai si [label] (brut, tel que renvoyé par SAM) désigne un corps qui
     * bloque la trajectoire : une personne ([isPerson]) **ou** ce qui se
     * contourne comme elle — poussette, fauteuil roulant. Pluriel toléré
     * (« poussettes », « strollers ») ; label vide ou inconnu = faux.
     */
    fun isBody(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in KEYS || key in BODY_KEYS) return true
        if (key.length <= 3 || !key.endsWith("s")) return false
        val singular = key.dropLast(1)
        return singular in KEYS || singular in BODY_KEYS
    }
}
