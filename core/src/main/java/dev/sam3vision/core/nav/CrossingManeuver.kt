package dev.sam3vision.core.nav

import dev.sam3vision.core.distance.KnownSizes

/**
 * Manœuvre de traversée de chaussée : Valhalla n'a pas de type dédié
 * (la traversée arrive en type 8, « tout droit », sans `street_names`) —
 * seul le texte la désigne (« Continuez sur le passage protégé. »,
 * constaté sur le backend IDF, place de la Concorde).
 *
 * [matches] lit la consigne écrite et la consigne parlée, canonisées par
 * [KnownSizes.canonical] (casse, accents, ponctuation neutralisés) : un
 * marqueur suffit. « Passage » seul ne compte pas — une rue peut
 * s'appeler « Passage du Souvenir » (constaté, Champs-Élysées).
 * Pur JVM, partagé par les deux chemins de parse ([ValhallaRoute.parse]
 * et [RouteTrip.toNavRoute]).
 */
object CrossingManeuver {

    /** Marqueurs canoniques (voir [KnownSizes.canonical]). */
    private val MARKERS = listOf(
        "passage protege",
        "passage pieton",
        "traversez",
        "crosswalk",
    )

    /**
     * Vrai si [instruction] ou [verbal] désigne une traversée de chaussée.
     * Textes vides ou sans marqueur ⇒ `false` (pas de traversée inventée).
     */
    fun matches(instruction: String, verbal: String): Boolean {
        if (instruction.isBlank() && verbal.isBlank()) return false
        val text = KnownSizes.canonical("$instruction $verbal")
        return MARKERS.any { text.contains(it) }
    }
}
