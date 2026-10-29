package dev.sam3vision.core.net

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapBounds
import java.util.Locale
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Feux tricolores : garde-fou à 15 s, comme l'itinéraire. */
const val SIGNALS_TIMEOUT_SECONDS: Long = 15L

/** User-Agent propre aux feux, identifiable côté backend. */
internal const val SIGNALS_USER_AGENT = "$USER_AGENT signals"

/** Garde-fou : au-delà, les feux excédentaires sont ignorés. */
internal const val SIGNALS_MAX_NODES = 2000

/**
 * Client des feux tricolores (`GET /signals` sur la base Valhalla) : le
 * backend proxifie OpenStreetMap (`highway=traffic_signals`) avec cache —
 * Valhalla ne les expose pas. Tout échec devient un [Result.failure] et
 * l'appelant dégrade (carte sans feux, jamais bloquante).
 */
interface SignalsClient {
    suspend fun signals(bounds: MapBounds): Result<List<RoutePoint>>
}

/**
 * Implémentation HTTP réelle : `GET {base}/signals?s=&w=&n=&e=`. Même socle
 * que [HttpRouteClient] ([requestTo], [defaultHttpClient], [Json.kt]) :
 * rien de construit à la main ici.
 */
class HttpSignalsClient(
    private val baseUrl: () -> String,
    private val client: OkHttpClient = defaultHttpClient(SIGNALS_TIMEOUT_SECONDS),
) : SignalsClient {

    override suspend fun signals(bounds: MapBounds): Result<List<RoutePoint>> =
        client.executeBuilt {
            requestTo(baseUrl().trim().trimEnd('/'), signalsPath(bounds), "GET", null, SIGNALS_USER_AGENT)
        }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseSignalsJson(raw) } }
}

/**
 * Chemin `/signals?s=&w=&n=&e=` : cadre à 5 décimales (~1 m, sans bruit
 * flottant) ; la marge et le cache vivent côté serveur.
 */
internal fun signalsPath(bounds: MapBounds): String =
    String.format(
        Locale.US, "/signals?s=%.5f&w=%.5f&n=%.5f&e=%.5f",
        bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon,
    )

/**
 * Réponse `/signals` → positions des feux : entrées avec `lat`/`lon` finis
 * (les autres sont ignorées, jamais d'exception partielle), plafonnées à
 * [SIGNALS_MAX_NODES]. JSON illisible ou sans `signals` : [ApiError.BadResponse].
 */
internal fun parseSignalsJson(raw: String): List<RoutePoint> {
    val json = try {
        JSONObject(raw)
    } catch (_: Exception) {
        throw ApiError.BadResponse("signaux : JSON illisible")
    }
    if (!json.has("signals")) throw ApiError.BadResponse("signaux : signals manquant")
    return json.objects("signals")
        .mapNotNull { element ->
            val lat = element.optDouble("lat", Double.NaN)
            val lon = element.optDouble("lon", Double.NaN)
            if (lat.isFinite() && lon.isFinite()) RoutePoint(lat, lon) else null
        }
        .take(SIGNALS_MAX_NODES)
}
