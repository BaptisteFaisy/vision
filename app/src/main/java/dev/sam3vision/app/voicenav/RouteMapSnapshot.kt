package dev.sam3vision.app.voicenav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapBounds
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.mapBoundsOf
import kotlinx.coroutines.delay

/** Relit (itinéraire, position) à 1 Hz, comme la carte de fond. */
internal const val ROUTE_MAP_POLL_MS = 1_000L

/** Échec des feux : on ne retente qu'après 60 s (pas de martèlement). */
internal const val SIGNALS_RETRY_MS = 60_000L

/** Instantané carte : tracé + position + feux (mini-carte et fond partagés). */
data class RouteMapSnapshot(
    val points: List<RoutePoint>,
    val position: RoutePoint?,
    val accuracyM: Float?,
    val signals: List<RoutePoint>,
)

/**
 * Tracé + position + feux à partir de l'itinéraire guidé ; `null` sans
 * point fini. Le fix doit être exploitable ([NavFix.isUsable]), sinon la
 * position est inconnue (le tracé reste affiché).
 */
internal fun routeMapSnapshotOf(
    route: NavRoute?,
    fix: NavFix?,
    signals: List<RoutePoint> = emptyList(),
): RouteMapSnapshot? {
    val points = route?.maneuvers.orEmpty()
        .map { RoutePoint(it.lat, it.lon) }
        .filter { it.lat.isFinite() && it.lon.isFinite() }
    if (points.isEmpty()) return null
    val usable = fix?.takeIf { it.isUsable() }
    return RouteMapSnapshot(
        points = points,
        position = usable?.let { RoutePoint(it.lat, it.lon) },
        accuracyM = usable?.accM,
        signals = signals.filter { it.lat.isFinite() && it.lon.isFinite() },
    )
}

/**
 * Clé stable d'un itinéraire (recalcul = nouvelle clé = nouveaux feux) :
 * nombre de manœuvres + premier/dernier points ; `"none"` sans itinéraire.
 */
internal fun routeKeyOf(route: NavRoute?): String {
    val maneuvers = route?.maneuvers.orEmpty()
    if (maneuvers.isEmpty()) return "none"
    val first = maneuvers.first()
    val last = maneuvers.last()
    return "${maneuvers.size}:${fmt(first.lat)},${fmt(first.lon)}:${fmt(last.lat)},${fmt(last.lon)}"
}

/** Coordonnée en clé stable (5 décimales, sans locale ni flottant texte). */
private fun fmt(value: Double): String = (value * 100000).toLong().toString()

/** Feux en cache : `failedAtMs` (`null` = succès) décide du retentative. */
private data class CachedSignals(val signals: List<RoutePoint>, val failedAtMs: Long?)

/** Vrai s'il faut (re)charger les feux : jamais vus, ou échec périmé. */
internal fun shouldFetchSignals(cached: List<RoutePoint>?, failedAtMs: Long?, nowMs: Long): Boolean {
    if (cached == null) return true
    if (failedAtMs == null) return false
    return nowMs - failedAtMs >= SIGNALS_RETRY_MS
}

/**
 * État carte vivant, partagé par la mini-carte et le fond : relit
 * (itinéraire, position) à 1 Hz, charge les feux une fois par itinéraire
 * ([fetchSignals] sur le cadre du tracé ; échec = liste vide + pause).
 * `null` sans tracé (repos) — l'appelant ne compose alors rien.
 */
@Composable
internal fun rememberRouteMapSnapshot(
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    fetchSignals: suspend (MapBounds) -> List<RoutePoint>,
): State<RouteMapSnapshot?> {
    val state = remember { mutableStateOf<RouteMapSnapshot?>(null) }
    LaunchedEffect(snapshot, fetchSignals) {
        val cache = mutableMapOf<String, CachedSignals>()
        while (true) {
            val (route, fix) = runCatching { snapshot() }.getOrNull() ?: Pair(null, null)
            val key = routeKeyOf(route)
            val bounds = route?.let { mapBoundsOf(it.maneuvers.map { m -> RoutePoint(m.lat, m.lon) }) }
            val cached = cache[key]
            val now = System.currentTimeMillis()
            val signals = if (bounds == null) {
                emptyList()
            } else if (cached != null && !shouldFetchSignals(cached.signals, cached.failedAtMs, now)) {
                cached.signals
            } else {
                val fetched = runCatching { fetchSignals(bounds) }
                val found = fetched.getOrElse { emptyList() }
                cache[key] = CachedSignals(found, if (fetched.isFailure) now else null)
                found
            }
            state.value = routeMapSnapshotOf(route, fix, signals)
            delay(ROUTE_MAP_POLL_MS)
        }
    }
    return state
}
