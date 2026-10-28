package dev.sam3vision.core.nav

/**
 * Position GPS exploitable par le guidage : coordonnées WGS84, précision
 * annoncée par le fournisseur ([accM], 1σ en mètres) et horodatage
 * ([atMs], `Location.time`, base des déterminismes — le moteur ne lit
 * jamais l'horloge).
 */
data class NavFix(
    val lat: Double,
    val lon: Double,
    val accM: Float,
    val atMs: Long,
) {
    /** Coordonnées finies et précision positive : sinon le fix est ignoré. */
    fun isUsable(): Boolean =
        lat.isFinite() && lon.isFinite() && accM.isFinite() && accM > 0f
}

/**
 * Action d'une manœuvre, vocabulaire Valhalla réduit au piéton :
 * [Continue] est le repli sûr (bretelle, ferry, transport, type inconnu).
 */
enum class ManeuverAction {
    Start,
    Continue,
    SlightLeft,
    TurnLeft,
    SlightRight,
    TurnRight,
    UTurn,
    Roundabout,
    Arrive,
}

/**
 * Une manœuvre de l'itinéraire : [action] à annoncer au point ([lat], [lon]),
 * [street] portant le nom de la voie visée (`null` si Valhalla n'en donne
 * pas — l'annonce omet alors la rue au lieu d'inventer). [crossing] vraie =
 * traversée de chaussée (passage protégé) : le moteur y suspend la consigne
 * de progression et y émet les événements traversée (voir [NavEvent]).
 */
data class NavManeuver(
    val action: ManeuverAction,
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val crossing: Boolean = false,
)

/**
 * Itinéraire guidable : manœuvres ordonnées du départ à l'arrivée.
 * La dernière manœuvre porte [ManeuverAction.Arrive] et son point est la
 * destination ; [destinationName] (`null` si inconnu) habille l'annonce
 * d'arrivée (« Vous êtes arrivé à X »).
 */
data class NavRoute(
    val maneuvers: List<NavManeuver>,
    val destinationName: String? = null,
) {
    companion object {
        /**
         * Valide un itinéraire candidat : au moins départ + arrivée, première
         * manœuvre [ManeuverAction.Start], dernière [ManeuverAction.Arrive],
         * coordonnées finies partout. Échec [RouteError] sinon — ne lève jamais.
         */
        fun validate(route: NavRoute): Result<NavRoute> {
            if (route.maneuvers.size < 2) return Result.failure(RouteError.TooFewManeuvers())
            if (route.maneuvers.first().action != ManeuverAction.Start) {
                return Result.failure(RouteError.MissingStart())
            }
            if (route.maneuvers.last().action != ManeuverAction.Arrive) {
                return Result.failure(RouteError.MissingArrival())
            }
            route.maneuvers.forEachIndexed { index, maneuver ->
                if (!maneuver.lat.isFinite() || !maneuver.lon.isFinite()) {
                    return Result.failure(RouteError.BadPoint(index))
                }
            }
            return Result.success(route)
        }
    }
}

/**
 * Itinéraire refusé par [NavRoute.validate] : messages français affichables,
 * jamais levés — renvoyés par [Result.failure].
 */
sealed class RouteError(message: String) : Exception(message) {
    /** Moins de deux manœuvres : pas de départ ni d'arrivée à guider. */
    class TooFewManeuvers : RouteError("itinéraire vide : départ et arrivée requis")

    /** La première manœuvre n'est pas un départ. */
    class MissingStart : RouteError("itinéraire sans point de départ")

    /** La dernière manœuvre n'est pas une arrivée. */
    class MissingArrival : RouteError("itinéraire sans point d'arrivée")

    /** Coordonnées non finies à la manœuvre [index]. */
    class BadPoint(val index: Int) : RouteError("manœuvre $index sans coordonnées")
}
