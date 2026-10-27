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
import dev.sam3vision.core.share.SharedScene
import kotlinx.coroutines.delay

/** Même publication pour Direct et iSee : le service reprend dès que l'écran s'efface. */
@Composable
fun ShareRelay(layer: DistanceLayer?, settings: Settings, scene: SharedScene? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val broadcaster = remember(context) { ShareBroadcaster(context) }
    val currentLayer by rememberUpdatedState(layer)
    val currentScene by rememberUpdatedState(scene)
    LaunchedEffect(settings, lifecycle) {
        if (!settings.shareEnabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            broadcaster.start(true)
            ShareGate.screenPublishing = true
            try {
                while (true) {
                    broadcaster.publish(System.currentTimeMillis(), settings, currentLayer, currentScene)
                    delay(SHARE_INTERVAL_MS)
                }
            } finally {
                ShareGate.screenPublishing = false
                broadcaster.stop()
            }
        }
    }
}
