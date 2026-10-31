package dev.sam3vision.core.model

/**
 * Un point d'un tracé d'itinéraire : degrés décimaux WGS84, comme les
 * `locations` Valhalla et le GPS du téléphone — aucune conversion à l'usage.
 */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
)

/**
 * Une manœuvre d'un itinéraire Valhalla : le `type` numérique du serveur
 * (1 = départ, 4 = arrivée, 8 = tout droit… — voir `maneuverShort`), les
 * consignes du serveur (anglais demandé à la requête), les noms de rue et le
 * coût du pas. Longueur en **mètres**, temps en **secondes** : le serveur parle
 * en kilomètres, la conversion est faite au parsing, une fois pour toutes.
 */
data class RouteManeuver(
    val type: Int,
    val instruction: String,
    val verbalInstruction: String,
    val streetNames: List<String>,
    val lengthMeters: Double,
    val timeSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
)

/**
 * Un tronçon d'itinéraire : le tracé **décodé** (un point par sommet Valhalla)
 * et ses manœuvres, dont les index `begin/endShapeIndex` pointent dans [shape].
 */
data class RouteLeg(
    val shape: List<RoutePoint>,
    val maneuvers: List<RouteManeuver>,
    val lengthMeters: Double,
    val timeSeconds: Double,
)

/**
 * Un itinéraire piéton complet : un tronçon par paire d'étapes (d'ici à la
 * destination : un seul). Les totaux viennent du `summary` du serveur.
 */
data class RouteTrip(
    val legs: List<RouteLeg>,
    val lengthMeters: Double,
    val timeSeconds: Double,
) {
    /** Toutes les manœuvres, dans l'ordre de marche. */
    val maneuvers: List<RouteManeuver> get() = legs.flatMap { it.maneuvers }

    /** Le tracé complet, tronçons bout à bout. */
    val shape: List<RoutePoint> get() = legs.flatMap { it.shape }
}
