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

/**
 * Seuils d'expansion des véhicules (PISTE2) : un véhicule est un danger
 * grave — seuil de grossissement abaissé (0,02), couloir élargi (0,30, un
 * véhicule large menace même excentré), TTC plafonné à 4 s. Priors à recaler
 * sur téléphone, comme [VehicleApproach.TTC_SECONDS].
 */
val VEHICLE_APPROACH_THRESHOLDS = ApproachThresholds(
    minGrowthPerSec = 0.02f,
    centerMargin = 0.30f,
    maxTtcSec = 4f,
)

/**
 * Seuils d'expansion par label (PISTE2) : [vehicle] pour les labels de
 * [VehicleConcepts], [person] pour tout le reste.
 */
fun approachThresholdsFor(
    label: String,
    vehicle: ApproachThresholds = VEHICLE_APPROACH_THRESHOLDS,
    person: ApproachThresholds = ApproachThresholds(),
): ApproachThresholds = if (VehicleConcepts.isVehicle(label)) vehicle else person
