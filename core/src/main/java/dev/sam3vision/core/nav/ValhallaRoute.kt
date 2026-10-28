package dev.sam3vision.core.nav

import org.json.JSONObject

/**
 * Adapte la réponse Valhalla `/route` (JSON `trip`, backend r630) en
 * [NavRoute] guidable : manœuvres de la première étape + géométrie
 * `shape` (polyligne encodée, précision 6).
 *
 * Pur JVM (`org.json`, comme le reste du core), ne lève jamais : JSON
 * malformé, étape absente, `shape` illisible ou index hors limites ⇒
 * [Result.failure] typé [RouteParseError].
 */
object ValhallaRoute {

    /** Précision Valhalla : la polyligne encode des micro-degrés ×10⁻⁶. */
    const val SHAPE_PRECISION = 1e-6

    /**
     * Construit l'itinéraire depuis le corps JSON de `/route`.
     * [destinationName] nomme l'arrivée (« Vous êtes arrivé à X ») ; sans
     * lui, la rue de la dernière manœuvre est reprise, ou rien.
     */
    fun parse(text: String, destinationName: String? = null): Result<NavRoute> {
        val root = readRoot(text) ?: return Result.failure(RouteParseError.InvalidJson())
        val leg = readFirstLeg(root) ?: return Result.failure(RouteParseError.MissingLeg())
        val points = decodeShape(leg.optString("shape", ""))
            ?: return Result.failure(RouteParseError.BadShape())
        val raw = leg.optJSONArray("maneuvers") ?: return Result.failure(RouteParseError.EmptyManeuvers())
        if (raw.length() < 2) return Result.failure(RouteParseError.EmptyManeuvers())
        val maneuvers = ArrayList<NavManeuver>(raw.length())
        for (i in 0 until raw.length()) {
            val maneuver = maneuverAt(raw.optJSONObject(i), points)
                ?: return Result.failure(RouteParseError.BadShapeIndex(i))
            maneuvers.add(maneuver)
        }
        // Contrat moteur : départ puis arrivée, quoi que dise le serveur.
        maneuvers[0] = maneuvers[0].copy(action = ManeuverAction.Start)
        maneuvers[maneuvers.lastIndex] = maneuvers[maneuvers.lastIndex].copy(action = ManeuverAction.Arrive)
        val name = destinationName?.takeIf { it.isNotBlank() }
            ?: maneuvers.last().street
        return Result.success(NavRoute(maneuvers = maneuvers, destinationName = name))
    }

    /** Racine JSON, `null` si le texte n'est pas un objet. */
    private fun readRoot(text: String): JSONObject? =
        try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }

    /** Première étape de `trip.legs`, `null` si absente. */
    private fun readFirstLeg(root: JSONObject): JSONObject? =
        try {
            root.optJSONObject("trip")?.optJSONArray("legs")?.optJSONObject(0)
        } catch (_: Exception) {
            null
        }

    /**
     * Une manœuvre : point = `shape[begin_shape_index]`, rue = premier
     * nom Valhalla. `null` si l'objet manque ou si l'index sort du tracé.
     */
    private fun maneuverAt(node: JSONObject?, points: List<Pair<Double, Double>>): NavManeuver? {
        node ?: return null
        val shapeIndex = node.optInt("begin_shape_index", -1)
        if (shapeIndex < 0 || shapeIndex >= points.size) return null
        val (lat, lon) = points[shapeIndex]
        val streets = node.optJSONArray("street_names")
        val street = if (streets != null && streets.length() > 0) {
            streets.optString(0, "").trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return NavManeuver(action = mapType(node.optInt("type", 8)), lat = lat, lon = lon, street = street)
    }

    /**
     * Type Valhalla → action piétonne (table `maneuver.h` : 1 départ,
     * 4 arrivée, 8 tout droit, 9/10/11 droite, 15/16 gauche, 12/13 demi-tour,
     * 26/27 giratoire…). Bretelles, ferries, transports et inconnus ⇒
     * [ManeuverAction.Continue] (repli sûr : on ne tourne pas).
     */
    fun mapType(type: Int): ManeuverAction = when (type) {
        1, 2, 3 -> ManeuverAction.Start
        4, 5, 6 -> ManeuverAction.Arrive
        9, 18, 20, 23, 37 -> ManeuverAction.SlightRight
        10, 11 -> ManeuverAction.TurnRight
        12, 13 -> ManeuverAction.UTurn
        14, 15 -> ManeuverAction.TurnLeft
        16, 19, 21, 24, 38 -> ManeuverAction.SlightLeft
        26, 27 -> ManeuverAction.Roundabout
        else -> ManeuverAction.Continue
    }

    /**
     * Décode une polyligne encodée (précision 6) en points (lat, lon).
     * `null` si un caractère est hors alphabet ou si la chaîne est tronquée.
     */
    fun decodeShape(shape: String): List<Pair<Double, Double>>? {
        if (shape.isEmpty()) return null
        val points = ArrayList<Pair<Double, Double>>(shape.length / 4)
        var index = 0
        var lat = 0
        var lon = 0
        while (index < shape.length) {
            val dLat = readDelta(shape, index) ?: return null
            index = dLat.second
            val dLon = readDelta(shape, index) ?: return null
            index = dLon.second
            lat += dLat.first
            lon += dLon.first
            points.add(Pair(lat * SHAPE_PRECISION, lon * SHAPE_PRECISION))
        }
        return points.takeIf { it.isNotEmpty() }
    }

    /**
     * Lit un delta en zigzag à [from] : paire (valeur, index de reprise),
     * `null` si la chaîne est tronquée ou hors alphabet.
     */
    private fun readDelta(shape: String, from: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var index = from
        while (index < shape.length) {
            val code = shape[index].code - 63
            if (code < 0 || code > 63) return null
            index++
            value = value or ((code and 0x1F) shl shift)
            shift += 5
            if (code and 0x20 == 0) {
                val delta = if (value and 1 == 0) value shr 1 else (value shr 1).inv()
                return Pair(delta, index)
            }
        }
        return null
    }
}

/**
 * Réponse Valhalla inexploitable : messages français affichables, jamais
 * levés — renvoyés par [Result.failure].
 */
sealed class RouteParseError(message: String) : Exception(message) {
    /** Le corps n'est pas du JSON objet. */
    class InvalidJson : RouteParseError("réponse d'itinéraire illisible")

    /** Pas de `trip.legs[0]` dans la réponse. */
    class MissingLeg : RouteParseError("itinéraire sans étape")

    /** `shape` vide, tronquée ou hors alphabet. */
    class BadShape : RouteParseError("tracé d'itinéraire illisible")

    /** Trop peu de manœuvres (départ + arrivée requis). */
    class EmptyManeuvers : RouteParseError("itinéraire sans manœuvre")

    /** `begin_shape_index` de la manœuvre [index] hors du tracé. */
    class BadShapeIndex(val index: Int) : RouteParseError("manœuvre $index hors tracé")
}
