package dev.sam3vision.core.nav

/**
 * Résumé d'itinéraire vers une destination : ce que l'écran de confirmation
 * affiche (adresse + distance + durée) avant le départ.
 *
 * [destination] est le libellé dicté ou normalisé (« Gare de Lyon »),
 * [address] la ligne d'adresse postale quand le moteur d'itinéraire la
 * connaît (`null` = inconnue, l'écran montre la destination seule).
 * Distances en mètres, durée en secondes, toujours >= 0.
 *
 * Pur JVM : aucune dépendance Android, formatage français testé sans
 * Robolectric.
 */
data class RouteSummary(
    val destination: String,
    val address: String?,
    val distanceMeters: Long,
    val durationSeconds: Long,
) {
    init {
        require(destination.isNotBlank()) { "destination vide" }
        require(distanceMeters >= 0) { "distance négative" }
        require(durationSeconds >= 0) { "durée négative" }
    }

    /** « 350 m », « 2,4 km » (virgule française, une décimale au-delà du km). */
    fun distanceFr(): String = formatDistanceFr(distanceMeters)

    /** « moins d'une minute », « 12 min », « 1 h 05 ». */
    fun durationFr(): String = formatDurationFr(durationSeconds)
}

/**
 * Distance en français : mètres ronds sous 1 km, kilomètres à une décimale
 * au-delà (virgule, pas de point). Les négatifs sont ramenés à zéro.
 */
fun formatDistanceFr(meters: Long): String {
    val safe = meters.coerceAtLeast(0)
    if (safe < 1000) return "$safe m"
    val whole = safe / 1000
    val tenth = (safe % 1000) / 100
    return "$whole,$tenth km"
}

/**
 * Durée en français : secondes arrondies à la minute, heures au-delà de
 * 59 min (« 1 h 05 », minutes sur deux chiffres). Zéro = « moins d'une
 * minute », jamais « 0 min ».
 */
fun formatDurationFr(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    if (safe < 60) return "moins d'une minute"
    val minutes = (safe + 30) / 60
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    return "$hours h ${rest.toString().padStart(2, '0')}"
}
