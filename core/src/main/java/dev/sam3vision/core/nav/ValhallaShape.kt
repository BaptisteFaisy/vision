package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint

/**
 * Précision du `shape` Valhalla : 6 décimales (le défaut du serveur — un
 * `shape` à 5 décimales placerait Paris à des centaines de mètres de Paris).
 */
const val VALHALLA_SHAPE_PRECISION = 1e6

/**
 * Décode un tracé Valhalla (polyline encodée, précision 6) en points WGS84.
 * Pur JVM, sans dépendance : le client comme le guidage vocal s'en servent.
 *
 * Tolérant : un tracé coupé au milieu d'un pas garde les points déjà décodés
 * au lieu de tout perdre — une fin de tracé abîmée ne doit pas effacer le début.
 */
fun decodeValhallaShape(shape: String): List<RoutePoint> {
    val points = mutableListOf<RoutePoint>()
    var index = 0
    var lat = 0
    var lon = 0
    while (index < shape.length) {
        val (deltaLat, afterLat) = decodeChunk(shape, index) ?: break
        val (deltaLon, afterLon) = decodeChunk(shape, afterLat) ?: break
        index = afterLon
        lat += deltaLat
        lon += deltaLon
        points.add(RoutePoint(lat / VALHALLA_SHAPE_PRECISION, lon / VALHALLA_SHAPE_PRECISION))
    }
    return points
}

/**
 * Un pas de la polyline (latitude **ou** longitude) : valeur signée et index
 * de reprise. `null` si la chaîne se termine au milieu du pas (tracé coupé).
 */
private fun decodeChunk(shape: String, from: Int): Pair<Int, Int>? {
    var result = 0
    var shift = 0
    var index = from
    while (index < shape.length) {
        val byte = shape[index].code - 63
        index++
        result = result or ((byte and 0x1F) shl shift)
        shift += 5
        if (byte < 0x20) {
            val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            return delta to index
        }
    }
    return null
}

/**
 * Libellé parlé français d'un `type` de manœuvre Valhalla. **Repli** : quand
 * le serveur envoie ses consignes (`verbal_pre_transition_instruction`, français
 * demandé), ce sont elles qui sont dites ; ce libellé ne sert que si elles
 * manquent — une consigne courte vaut mieux que le silence au milieu d'un
 * guidage. Les types de transport en commun retombent sur « Continuez. » : le
 * guidage vocal est piéton, il ne prend ni le bus ni le ferry pour l'usager.
 */
fun maneuverShortFr(type: Int): String = when (type) {
    1, 2, 3 -> "Départ."
    4, 5, 6 -> "Arrivée."
    8, 22 -> "Continuez tout droit."
    9 -> "Légèrement à droite."
    10 -> "Tournez à droite."
    11 -> "Fortement à droite."
    12, 13 -> "Faites demi-tour."
    14 -> "Fortement à gauche."
    15 -> "Tournez à gauche."
    16 -> "Légèrement à gauche."
    17, 18, 19 -> "Prenez la rampe."
    20, 21 -> "Prenez la sortie."
    23 -> "Restez à droite."
    24 -> "Restez à gauche."
    25, 37, 38 -> "Insérez-vous."
    26 -> "Entrez dans le rond-point."
    27 -> "Sortez du rond-point."
    28 -> "Prenez le ferry."
    29 -> "Quittez le ferry."
    else -> "Continuez."
}
