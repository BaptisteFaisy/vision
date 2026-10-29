package dev.sam3vision.app.voicenav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.routePointsOf
import dev.sam3vision.core.nav.snappedPositionOf
import kotlinx.coroutines.delay

/** Relit (itinéraire, position) à 1 Hz, comme la carte de fond. */
internal const val ROUTE_MAP_POLL_MS = 1_000L

/** Instantané carte : tracé (shape) + position recalée (cartes partagées). */
data class RouteMapSnapshot(
    val points: List<RoutePoint>,
    val position: RoutePoint?,
    val accuracyM: Float?,
)

/**
 * Tracé + position recalée à partir de l'itinéraire guidé ; `null` sans
 * point. La position est recalée sur le tracé ([snappedPositionOf]) —
 * `null` si le fix est inexploitable (le tracé reste affiché).
 */
internal fun routeMapSnapshotOf(route: NavRoute?, fix: NavFix?): RouteMapSnapshot? {
    val points = routePointsOf(route)
    if (points.isEmpty()) return null
    val usable = fix?.takeIf { it.isUsable() }
    return RouteMapSnapshot(
        points = points,
        position = snappedPositionOf(fix, points),
        accuracyM = usable?.accM,
    )
}

/**
 * État carte vivant, partagé par la mini-carte et le fond : relit
 * (itinéraire, position) à 1 Hz. `null` sans tracé (repos) — l'appelant
 * ne compose alors rien.
 */
@Composable
internal fun rememberRouteMapSnapshot(
    snapshot: () -> Pair<NavRoute?, NavFix?>,
): State<RouteMapSnapshot?> {
    val state = remember { mutableStateOf<RouteMapSnapshot?>(null) }
    LaunchedEffect(snapshot) {
        while (true) {
            val (route, fix) = runCatching { snapshot() }.getOrNull() ?: Pair(null, null)
            state.value = routeMapSnapshotOf(route, fix)
            delay(ROUTE_MAP_POLL_MS)
        }
    }
    return state
}
