package dev.sam3vision.app.isee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.sam3vision.app.ShareRelay
import dev.sam3vision.app.voicenav.sharedTripOf
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.SharedScene
import dev.sam3vision.core.share.sharedPictosOf

/** The loved one gets the detections, this camera's picture, the trip and the current sentence. */
@Composable
internal fun IseeShare(state: IseeUiState, viewModel: IseeViewModel, settings: Settings) {
    val probe by viewModel.probe.state.collectAsState()
    val nav by viewModel.voiceNavState.collectAsState()
    // Camera live stream: frames leave over the room WebSocket.
    IseeLiveStream(settings)
    ShareRelay(
        layer = if (state.recordingVideo) probe.distanceLayer else null,
        settings = settings,
        scene = sharedSceneOf(state, probe),
        trip = sharedTripOf(nav),
        photo = SharedCameraFrame::photoOf,
        pictos = { now -> sharedPictosOf(state.picto.hits, viewModel.picto::pngOf, now) },
    )
}

/** An instruction never becomes a claim that a move happened. */
internal fun sharedSceneOf(state: IseeUiState, probe: LiveProbeState): SharedScene? {
    if (state.recordingVideo && !probe.spoken.isNullOrBlank()) return SharedScene(probe.spoken)
    val entry = selectedEntry(state)
    if (entry == null || entry is ChatEntry.IntroBubble || entry.text.isBlank()) return null
    return SharedScene(entry.text, entry is ChatEntry.UserBubble, state.speakingEntryId == entry.id)
}
