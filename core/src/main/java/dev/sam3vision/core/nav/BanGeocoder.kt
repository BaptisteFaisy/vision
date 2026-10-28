package dev.sam3vision.core.nav

import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.catchingResult
import dev.sam3vision.core.net.defaultHttpClient
import dev.sam3vision.core.net.execute
import dev.sam3vision.core.net.joinUrl
import dev.sam3vision.core.net.objects
import dev.sam3vision.core.net.request
import dev.sam3vision.core.net.requireSuccess
import dev.sam3vision.core.net.str
import dev.sam3vision.core.net.then
import java.net.URLEncoder
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject

/**
 * Repli de géocodage : Base Adresse Nationale (`GET {base}/search/?q=…&limit=…`,
 * sans clé). Base d'adresses française de référence — couvre les appareils sans
 * Play Services là où le géocodeur Android n'a pas de backend. Réponse GeoJSON :
 * `features[].geometry.coordinates = [lon, lat]`, `properties.{label,score}`.
 */
class BanGeocoder(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: OkHttpClient = defaultHttpClient(TIMEOUT_SECONDS),
    private val defaultLimit: Int = GeocodeSource.DEFAULT_LIMIT,
) : GeocodeSource {

    override suspend fun geocode(query: String, limit: Int): Result<List<GeocodedPlace>> {
        if (query.isBlank()) return Result.failure(GeocodeError.BlankQuery())
        val effectiveLimit = if (limit <= 0) defaultLimit else limit
        val url = buildUrl(baseUrl, query, effectiveLimit)
        return http.execute(request(url, "GET"))
            .then { it.requireSuccess() }
            .then { raw -> catchingResult { parsePlaces(raw) } }
            .mapError { it.toGeocodeError() }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api-adresse.data.gouv.fr"
        const val SEARCH_PATH = "/search/"
        const val TIMEOUT_SECONDS = 10L
        private const val MAX_LIMIT = 10

        /** URL encodée (`URLEncoder` : espaces en `+`, acceptés par la BAN). */
        internal fun buildUrl(base: String, query: String, limit: Int): String =
            joinUrl(base, SEARCH_PATH) + "?q=" +
                URLEncoder.encode(query, Charsets.UTF_8.name()) +
                "&limit=" + limit.coerceIn(1, MAX_LIMIT)

        /** GeoJSON -> candidats, meilleur score premier (ordre BAN conservé). */
        internal fun parsePlaces(raw: String): List<GeocodedPlace> {
            val root = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                throw ApiError.BadResponse("JSON illisible : ${raw.take(120)}")
            }
            return root.objects("features").mapNotNull { parseFeature(it) }
        }

        private fun parseFeature(feature: JSONObject): GeocodedPlace? {
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: return null
            if (coords.length() < 2) return null
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!isValidLatLon(lat, lon)) return null
            val props = feature.optJSONObject("properties") ?: JSONObject()
            return GeocodedPlace(lat, lon, pickLabel(props), props.scoreOrNull())
        }

        /** Coordonnées terrestres finies : le reste est un candidat à jeter. */
        internal fun isValidLatLon(lat: Double, lon: Double): Boolean =
            !lat.isNaN() && !lon.isNaN() &&
                lat in MIN_LATITUDE..MAX_LATITUDE && lon in MIN_LONGITUDE..MAX_LONGITUDE

        /** `label`, sinon `name, city`, sinon vide (les coordonnées priment). */
        internal fun pickLabel(props: JSONObject): String {
            val label = props.str("label")
            if (label.isNotBlank()) return label
            return listOf(props.str("name"), props.str("city"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }

        private fun JSONObject.scoreOrNull(): Float? {
            if (isNull("score")) return null
            val score = optDouble("score", Double.NaN)
            return score.takeUnless { it.isNaN() }?.toFloat()
        }

        private const val MIN_LATITUDE = -90.0
        private const val MAX_LATITUDE = 90.0
        private const val MIN_LONGITUDE = -180.0
        private const val MAX_LONGITUDE = 180.0
    }
}
