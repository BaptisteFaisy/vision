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
 * Pur JVM : aucune dépendance Android, formatage anglais testé sans
 * Robolectric.
 */
data class RouteSummary(
    val destination: String,
    val address: String?,
    val distanceMeters: Long,
    val durationSeconds: Long,
) {
    init {
        require(destination.isNotBlank()) { "empty destination" }
        require(distanceMeters >= 0) { "negative distance" }
        require(durationSeconds >= 0) { "negative duration" }
    }

    /** « 350 m », « 2.4 km » (point anglais, une décimale au-delà du km). */
    fun distance(): String = formatDistance(distanceMeters)

    /** « less than a minute », « 12 min », « 1 h 05 ». */
    fun duration(): String = formatDuration(durationSeconds)

    /** « less than a minute », « 12 minutes », « 1 hour, 5 minutes » (question vocale). */
    fun durationEn(): String = formatDurationEn(durationSeconds)
}

/**
 * Distance en anglais : mètres ronds sous 1 km, kilomètres à une décimale
 * au-delà (point, pas de virgule). Les négatifs sont ramenés à zéro.
 */
fun formatDistance(meters: Long): String {
    val safe = meters.coerceAtLeast(0)
    if (safe < 1000) return "$safe m"
    val whole = safe / 1000
    val tenth = (safe % 1000) / 100
    return "$whole.$tenth km"
}

/**
 * Durée en anglais : secondes arrondies à la minute, heures au-delà de
 * 59 min (« 1 h 05 », minutes sur deux chiffres). Zéro = « less than a
 * minute », jamais « 0 min ».
 */
fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    if (safe < 60) return "less than a minute"
    val minutes = (safe + 30) / 60
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    return "$hours h ${rest.toString().padStart(2, '0')}"
}

/**
 * Durée parlée en anglais (question de confirmation) : mêmes arrondis que
 * [formatDuration] (minute la plus proche, heures au-delà de 59 min),
 * minutes nulles omises (« 2 hours », pas « 2 hours, 0 minutes »).
 */
fun formatDurationEn(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    if (safe < 60) return "less than a minute"
    val minutes = (safe + 30) / 60
    if (minutes < 60) return "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
    val hours = minutes / 60
    val rest = minutes % 60
    val head = "$hours ${if (hours == 1L) "hour" else "hours"}"
    if (rest == 0L) return head
    return "$head, $rest ${if (rest == 1L) "minute" else "minutes"}"
}
