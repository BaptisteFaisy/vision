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
 * The live stream: the iSee camera goes to the caregiver page over the room
 * WebSocket, while sharing is on and the screen alive.
 *
 * Split from [IseeShare] on purpose — that one publishes the slow state
 * (position, sentence, trip, backup photo) every 2 s, this one pushes
 * pictures. Two mechanics, two lives: cutting the live stream never cuts
 * tracking, and back.
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
