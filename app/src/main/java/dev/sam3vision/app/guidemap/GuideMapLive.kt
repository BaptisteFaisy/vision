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
import dev.sam3vision.core.nav.GuidanceMapState
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.guidanceMapStateOf
import kotlinx.coroutines.delay

/**
 * État carte vivant : relit (itinéraire, position) à 1 Hz, état pur
 * (tracé shape + position recalée + caméra d'ensemble, sans signaux).
 * `null` sans tracé — l'appelant ne compose alors rien.
 */
@Composable
internal fun rememberGuidanceMapState(
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    update: GuidanceUpdate,
): State<GuidanceMapState?> {
    val state = remember { mutableStateOf<GuidanceMapState?>(null) }
    val currentUpdate by rememberUpdatedState(update)
    LaunchedEffect(snapshot) {
        while (true) {
            val (route, fix) = runCatching { snapshot() }.getOrNull() ?: Pair(null, null)
            state.value = guidanceMapStateOf(route, fix, currentUpdate)
                .takeIf { it.overlays.route.isNotEmpty() }
            delay(ROUTE_MAP_POLL_MS)
        }
    }
    return state
}

/**
 * Carte de l'écran de guidage : fond vectoriel quand le style charge,
 * schéma sinon (repli jusqu'à la sortie d'écran — rouvrir pour réessayer
 * les tuiles). Rien sans tracé (repos).
 */
@Composable
fun GuidanceMapCard(
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    update: GuidanceUpdate,
    modifier: Modifier = Modifier,
) {
    val mapState by rememberGuidanceMapState(snapshot, update)
    val current = mapState ?: return
    var tilesOk by remember { mutableStateOf(true) }
    if (tilesOk) {
        GuidanceMapView(current, modifier, onTilesError = { tilesOk = false })
    } else {
        MiniRouteMap(snapshot, modifier)
    }
}
