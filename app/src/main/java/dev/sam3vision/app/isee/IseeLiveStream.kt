package dev.sam3vision.app.isee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.sam3vision.app.ShareStreamer
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.canPublishShare
import dev.sam3vision.core.share.relayBaseOf
import dev.sam3vision.core.share.streamUrlOf

/**
 * Le direct : la caméra d'iSee part à la page de l'aidant par le WebSocket de la
 * salle, tant que le partage est activé et l'écran vivant.
 *
 * Séparé de [IseeShare] exprès — celui-ci publie l'état lent (position, phrase,
 * trajet, photo de secours) toutes les 2 s, celui-là pousse des images. Deux
 * mécaniques, deux vies : couper le direct ne coupe pas le suivi, et l'inverse
 * non plus.
 */
@Composable
internal fun IseeLiveStream(settings: Settings) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(settings, lifecycle) {
        if (!canPublishShare(settings)) return@LaunchedEffect
        val url = streamUrlOf(
            relayBaseOf(settings.parentSiteUrl),
            settings.shareId,
            settings.shareWriteKey,
        ) ?: return@LaunchedEffect
        val streamer = ShareStreamer()
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { streamer.run(url) }
        } finally {
            streamer.close()
        }
    }
}
