package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RouteLeg
import dev.sam3vision.core.model.RouteManeuver
import dev.sam3vision.core.model.RouteTrip

/**
 * Couture itinéraire → moteur : le [RouteTrip] du client Valhalla (tracé
 * décodé + manœuvres typées) devient le [NavRoute] guidable.
 *
 * Même contrat que [ValhallaRoute.parse] : première étape seule (nos requêtes
 * n'ont qu'une paire d'étapes), point = `shape[begin_shape_index]`, rue =
 * premier nom Valhalla, départ puis arrivée forcés quoi que dise le serveur.
 * Ne lève jamais : échec typé [RouteParseError] ou [RouteError].
 */
fun RouteTrip.toNavRoute(destinationName: String?): Result<NavRoute> {
    val leg = legs.firstOrNull() ?: return Result.failure(RouteParseError.MissingLeg())
    if (leg.maneuvers.size < 2) return Result.failure(RouteParseError.EmptyManeuvers())
    val maneuvers = ArrayList<NavManeuver>(leg.maneuvers.size)
    leg.maneuvers.forEachIndexed { index, maneuver ->
        maneuvers.add(maneuver.toNav(leg, index).getOrElse { return Result.failure(it) })
    }
    // Contrat moteur : départ puis arrivée, quoi que dise le serveur.
    maneuvers[0] = maneuvers[0].copy(action = ManeuverAction.Start)
    maneuvers[maneuvers.lastIndex] = maneuvers[maneuvers.lastIndex].copy(action = ManeuverAction.Arrive)
    val name = destinationName?.takeIf { it.isNotBlank() } ?: maneuvers.last().street
    return NavRoute.validate(NavRoute(maneuvers = maneuvers, destinationName = name, shape = leg.shape))
}

/**
 * Une manœuvre client → manœuvre guidable : type Valhalla réduit au piéton
 * ([ValhallaRoute.mapType]), point sur le tracé, rue ou rien (jamais inventée),
 * traversée lue dans les consignes ([CrossingManeuver]).
 */
private fun RouteManeuver.toNav(leg: RouteLeg, index: Int): Result<NavManeuver> {
    if (beginShapeIndex < 0 || beginShapeIndex >= leg.shape.size) {
        return Result.failure(RouteParseError.BadShapeIndex(index))
    }
    val point = leg.shape[beginShapeIndex]
    val street = streetNames.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return Result.success(
        NavManeuver(
            action = ValhallaRoute.mapType(type),
            lat = point.lat,
            lon = point.lon,
            street = street,
            crossing = CrossingManeuver.matches(instruction, verbalInstruction),
        ),
    )
}
