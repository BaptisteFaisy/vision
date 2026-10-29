package dev.sam3vision.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.SHARE_INTERVAL_MS
import dev.sam3vision.core.share.SharedPhoto
import dev.sam3vision.core.share.SharedScene
import dev.sam3vision.core.share.SharedTrip
import dev.sam3vision.core.share.canPublishShare
import kotlinx.coroutines.delay

/**
 * Même publication pour Direct et iSee : le service reprend dès que l'écran s'efface.
 *
 * [photo] est relue à chaque envoi (dernière frame caméra, `null` quand elle a
 * vieilli) et [trip] porte le guidage en cours : la page caregiver reçoit l'image
 * et la barre de progression sans qu'aucune des deux ne passe par l'état Compose.
 */
@Composable
fun ShareRelay(
    layer: DistanceLayer?,
    settings: Settings,
    scene: SharedScene? = null,
    trip: SharedTrip? = null,
    photo: (Long) -> SharedPhoto? = { null },
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val broadcaster = remember(context) { ShareBroadcaster(context) }
    val currentLayer by rememberUpdatedState(layer)
    val currentScene by rememberUpdatedState(scene)
    val currentTrip by rememberUpdatedState(trip)
    val currentPhoto by rememberUpdatedState(photo)
    LaunchedEffect(settings, lifecycle) {
        if (!canPublishShare(settings)) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            broadcaster.start(true)
            ShareGate.screenPublishing = true
            try {
                while (true) {
                    val now = System.currentTimeMillis()
                    broadcaster.publish(
                        now,
                        settings,
                        currentLayer,
                        currentScene,
                        currentTrip,
                        currentPhoto(now),
                    )
                    delay(SHARE_INTERVAL_MS)
                }
            } finally {
                ShareGate.screenPublishing = false
                broadcaster.stop()
            }
        }
    }
}
