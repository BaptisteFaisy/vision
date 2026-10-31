package dev.sam3vision.core.nav

/**
 * État instantané du guidage actif, poussé par le moteur d'itinéraire :
 * ce que l'écran de guidage affiche (prochaine instruction, distances,
 * progression) jusqu'au prochain [GuidanceUpdate].
 *
 * [instruction] est la prochaine manœuvre en anglais (« Turn right
 * onto rue de Rivoli »), [distanceToManeuverMeters] la distance avant de
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
        require(instruction.isNotBlank()) { "empty instruction" }
        require(distanceToManeuverMeters >= 0) { "negative maneuver distance" }
        require(remainingDistanceMeters >= 0) { "negative remaining distance" }
        require(remainingDurationSeconds >= 0) { "negative remaining duration" }
    }

    /** « In 150 m » : distance avant la manœuvre, format anglais. */
    fun maneuver(): String = "In ${formatDistance(distanceToManeuverMeters)}"

    /** « 2.4 km left · 12 min » : reste du trajet, format anglais. */
    fun remaining(): String =
        "${formatDistance(remainingDistanceMeters)} left · " +
            formatDuration(remainingDurationSeconds)
}

/**
 * Premier état affiché au départ : l'instruction pointe la destination, le
 * reste vaut le résumé entier, la progression est nulle.
 */
fun initialGuidance(summary: RouteSummary): GuidanceUpdate =
    GuidanceUpdate(
        instruction = "Heading to ${summary.destination}",
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
