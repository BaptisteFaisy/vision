package dev.sam3vision.app.guidemap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.sam3vision.app.voicenav.MiniRouteMap
import dev.sam3vision.app.voicenav.ROUTE_MAP_POLL_MS
import dev.sam3vision.app.voicenav.routeKeyOf
import dev.sam3vision.app.voicenav.shouldFetchSignals
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.GuidanceMapState
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.MapBounds
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.guidanceMapStateOf
import dev.sam3vision.core.nav.mapBoundsOf
import kotlinx.coroutines.delay

/** Feux en cache : `failedAtMs` (`null` = succès) décide de la retentative. */
private data class LiveSignals(val signals: List<RoutePoint>, val failedAtMs: Long?)

/**
 * État carte vivant : relit (itinéraire, position) à 1 Hz, charge les feux
 * une fois par tracé (échec = vide + pause 60 s, comme la mini-carte).
 * `null` sans tracé — l'appelant ne compose alors rien.
 */
@Composable
internal fun rememberGuidanceMapState(
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    fetchSignals: suspend (MapBounds) -> List<RoutePoint>,
    update: GuidanceUpdate,
): State<GuidanceMapState?> {
    val state = remember { mutableStateOf<GuidanceMapState?>(null) }
    val currentUpdate by rememberUpdatedState(update)
    LaunchedEffect(snapshot, fetchSignals) {
        val cache = mutableMapOf<String, LiveSignals>()
        while (true) {
            val (route, fix) = runCatching { snapshot() }.getOrNull() ?: Pair(null, null)
            state.value = liveMapState(route, fix, currentUpdate, fetchSignals, cache)
            delay(ROUTE_MAP_POLL_MS)
        }
    }
    return state
}

/** Un tick : feux du cache (ou chargés) + état pur, `null` sans tracé. */
private suspend fun liveMapState(
    route: NavRoute?,
    fix: NavFix?,
    update: GuidanceUpdate,
    fetchSignals: suspend (MapBounds) -> List<RoutePoint>,
    cache: MutableMap<String, LiveSignals>,
): GuidanceMapState? {
    val key = routeKeyOf(route)
    val bounds = route?.let { mapBoundsOf(it.maneuvers.map { m -> RoutePoint(m.lat, m.lon) }) }
    val entry = cache[key]
    val now = System.currentTimeMillis()
    val signals = if (bounds == null) {
        emptyList()
    } else if (entry != null && !shouldFetchSignals(entry.signals, entry.failedAtMs, now)) {
        entry.signals
    } else {
        val fetched = runCatching { fetchSignals(bounds) }
        val found = fetched.getOrElse { emptyList() }
        cache[key] = LiveSignals(found, if (fetched.isFailure) now else null)
        found
    }
    return guidanceMapStateOf(route, fix, update, signals)
        .takeIf { it.overlays.route.isNotEmpty() }
}

/**
 * Carte de l'écran de guidage : fond OSM quand les tuiles chargent, schéma
 * sinon (repli jusqu'à la sortie d'écran — rouvrir pour réessayer les
 * tuiles). Rien sans tracé (repos).
 */
@Composable
fun GuidanceMapCard(
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    fetchSignals: suspend (MapBounds) -> List<RoutePoint>,
    update: GuidanceUpdate,
    modifier: Modifier = Modifier,
) {
    val mapState by rememberGuidanceMapState(snapshot, fetchSignals, update)
    val current = mapState ?: return
    var tilesOk by remember { mutableStateOf(true) }
    if (tilesOk) {
        GuidanceMapView(current, modifier, onTilesError = { tilesOk = false })
    } else {
        MiniRouteMap(snapshot, fetchSignals, modifier)
    }
}
