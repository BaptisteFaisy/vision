package dev.sam3vision.core.nav

/**
 * État instantané du guidage actif, poussé par le moteur d'itinéraire :
 * ce que l'écran de guidage affiche (prochaine instruction, distances,
 * progression) jusqu'au prochain [GuidanceUpdate].
 *
 * [instruction] est la prochaine manœuvre en français (« Tournez à droite
 * sur la rue de Rivoli »), [distanceToManeuverMeters] la distance avant de
 * l'exécuter, [remainingDistanceMeters] / [remainingDurationSeconds] le reste
 * du trajet, [arrived] l'arrivée signalée par le moteur, [offRoute] l'écart
 * de route en cours (recalcul, bandeau affiché).
 *
 * Contrat [offRoute] (driver → écran) : le driver le lève à `true` sur
 * `NavEvent.OffRoute` et le rabaisse à `false` dès le premier état du nouvel
 * itinéraire (ou sur `BackOnRoute`) ; l'écran n'affiche que le bandeau, il ne
 * décide rien. Défaut `false` : les constructions existantes sont inchangées.
 *
 * Pur JVM : aucune dépendance Android ni coroutine.
 */
data class GuidanceUpdate(
    val instruction: String,
    val distanceToManeuverMeters: Long,
    val remainingDistanceMeters: Long,
    val remainingDurationSeconds: Long,
    val arrived: Boolean = false,
    val offRoute: Boolean = false,
) {
    init {
        require(instruction.isNotBlank()) { "instruction vide" }
        require(distanceToManeuverMeters >= 0) { "distance manœuvre négative" }
        require(remainingDistanceMeters >= 0) { "distance restante négative" }
        require(remainingDurationSeconds >= 0) { "durée restante négative" }
    }

    /** « Dans 150 m » : distance avant la manœuvre, format français. */
    fun maneuverFr(): String = "Dans ${formatDistanceFr(distanceToManeuverMeters)}"

    /** « Encore 2,4 km · 12 min » : reste du trajet, format français. */
    fun remainingFr(): String =
        "Encore ${formatDistanceFr(remainingDistanceMeters)} · " +
            formatDurationFr(remainingDurationSeconds)
}

/**
 * Premier état affiché au départ : l'instruction pointe la destination, le
 * reste vaut le résumé entier, la progression est nulle.
 */
fun initialGuidance(summary: RouteSummary): GuidanceUpdate =
    GuidanceUpdate(
        instruction = "Direction ${summary.destination}",
        distanceToManeuverMeters = summary.distanceMeters,
        remainingDistanceMeters = summary.distanceMeters,
        remainingDurationSeconds = summary.durationSeconds,
    )

/**
 * Progression du trajet entre 0 et 1, à partir du reste et du total initial
 * (mètres). Total nul ou négatif = 0 ; reste au-delà du total = 0 ; arrivé
 * (reste nul) = 1. Ne lève jamais.
 */
fun progressOf(remainingMeters: Long, totalMeters: Long): Float {
    if (totalMeters <= 0 || remainingMeters <= 0) {
        return if (remainingMeters <= 0 && totalMeters > 0) 1f else 0f
    }
    if (remainingMeters >= totalMeters) return 0f
    return (1.0 - remainingMeters.toDouble() / totalMeters.toDouble()).toFloat()
}
