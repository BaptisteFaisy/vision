package dev.sam3vision.core.nav

import java.net.URLEncoder

/**
 * Builder pur d'URIs Google Maps : navigation guidée, itinéraire universel et
 * recherche de lieu. 100 % JVM (`java.net.URLEncoder`), zéro dépendance Android,
 * donc testable en unit test local sans Robolectric.
 *
 * Trois formats stables (contrat verrouillé par `MapsUriTest`) :
 * - [navigation] : `google.navigation:q=<enc>&mode=w` (intent Google Maps) ;
 * - [directions] : `https://www.google.com/maps/dir/?api=1&destination=<enc>&travelmode=...`
 *   (URL universelle, marche sans l'app Maps) ;
 * - [search] : `https://www.google.com/maps/search/?api=1&query=<enc>` (fiche lieu).
 *
 * Bonus : [geo] (`geo:0,0?q=<enc>` ou `geo:<lat>,<lng>`), l'intent générique
 * Android compris aussi par d'autres apps de cartes.
 *
 * Encodage : [encode] applique le percent-encoding UTF-8 avec espace en `%20`
 * (forme URI, pas la forme `+` des formulaires). Les coordonnées `lat,lng`
 * ne sont jamais encodées (chiffres, `.`, `-` et `,` sont sûrs).
 *
 * Erreurs : jamais d'exception lancée — destination vide ou coordonnées hors
 * plages / non finies donnent `Result.failure(IllegalArgumentException)`.
 */
object MapsUri {

    /** Mode de déplacement : code intent (`mode=`) + code URL universelle (`travelmode=`). */
    enum class Mode(internal val intentCode: String, internal val dirCode: String) {
        Walking("w", "walking"),
        Driving("d", "driving"),
        Bicycling("b", "bicycling"),
        Transit("l", "transit"),
    }

    const val NAVIGATION_SCHEME = "google.navigation:q="
    const val DIRECTIONS_BASE = "https://www.google.com/maps/dir/?api=1"
    const val SEARCH_BASE = "https://www.google.com/maps/search/?api=1"
    const val GEO_QUERY_BASE = "geo:0,0?q="

    /**
     * Percent-encoding UTF-8 d'un fragment destiné à une URI Maps.
     * `URLEncoder` seul émettrait `+` pour l'espace (convention des formulaires
     * HTML) ; on normalise en `%20`, la forme attendue dans une URI.
     */
    fun encode(raw: String): String =
        URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")

    /** Intent `google.navigation:q=<destination>&mode=w` (piéton par défaut). */
    fun navigation(destination: String, mode: Mode = Mode.Walking): Result<String> =
        textOr(destination, "destination")
            .map { "$NAVIGATION_SCHEME${encode(it)}&mode=${mode.intentCode}" }

    /** Intent `google.navigation:q=<lat>,<lng>&mode=w`. */
    fun navigation(latitude: Double, longitude: Double, mode: Mode = Mode.Walking): Result<String> =
        coordsOr(latitude, longitude)
            .map { (lat, lng) -> "$NAVIGATION_SCHEME$lat,$lng&mode=${mode.intentCode}" }

    /** URL universelle `dir/?api=1&destination=<destination>&travelmode=...`. */
    fun directions(destination: String, mode: Mode = Mode.Walking): Result<String> =
        textOr(destination, "destination")
            .map { "$DIRECTIONS_BASE&destination=${encode(it)}&travelmode=${mode.dirCode}" }

    /** URL universelle `dir/?api=1&destination=<lat>,<lng>&travelmode=...`. */
    fun directions(latitude: Double, longitude: Double, mode: Mode = Mode.Walking): Result<String> =
        coordsOr(latitude, longitude)
            .map { (lat, lng) -> "$DIRECTIONS_BASE&destination=$lat,$lng&travelmode=${mode.dirCode}" }

    /** URL universelle `search/?api=1&query=<query>` (fiche lieu, sans guidage). */
    fun search(query: String): Result<String> =
        textOr(query, "query").map { "$SEARCH_BASE&query=${encode(it)}" }

    /** Intent générique `geo:0,0?q=<query>`. */
    fun geo(query: String): Result<String> =
        textOr(query, "query").map { "$GEO_QUERY_BASE${encode(it)}" }

    /** Intent générique `geo:<lat>,<lng>`. */
    fun geo(latitude: Double, longitude: Double): Result<String> =
        coordsOr(latitude, longitude).map { (lat, lng) -> "geo:$lat,$lng" }

    private fun textOr(value: String, name: String): Result<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("$name est vide"))
        }
        return Result.success(trimmed)
    }

    private fun coordsOr(latitude: Double, longitude: Double): Result<Pair<Double, Double>> {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return Result.failure(IllegalArgumentException("coordonnées non finies"))
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return Result.failure(IllegalArgumentException("coordonnées hors plages"))
        }
        return Result.success(latitude to longitude)
    }
}
