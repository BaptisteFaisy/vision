package dev.sam3vision.core.traverse

/**
 * Seuils d'expansion par type d'objet (PISTE2) : le grossissement minimal,
 * le couloir central et le TTC maximal alerté s'ajustent par label
 * (véhicules + personnes). Les défauts sont les seuils personnes historiques.
 */
data class ApproachThresholds(
    /** Taux minimal de grossissement (taille normalisée par seconde). */
    val minGrowthPerSec: Float = 0.03f,
    /** Demi-largeur du couloir central (|cx − 0,5| ≤ marge). */
    val centerMargin: Float = 0.25f,
    /** TTC maximal alerté (`null` = pas de filtre — personnes inchangées). */
    val maxTtcSec: Float? = null,
)
