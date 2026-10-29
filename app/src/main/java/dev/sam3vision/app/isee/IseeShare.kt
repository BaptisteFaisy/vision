package dev.sam3vision.app.isee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.sam3vision.app.ShareRelay
import dev.sam3vision.app.voicenav.sharedTripOf
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.SharedScene
import dev.sam3vision.core.share.sharedPictosOf

/** Le proche reçoit les détections, l'image de cette caméra, le trajet et la phrase courante. */
@Composable
internal fun IseeShare(state: IseeUiState, viewModel: IseeViewModel, settings: Settings) {
    val probe by viewModel.probe.state.collectAsState()
    val nav by viewModel.voiceNavState.collectAsState()
    ShareRelay(
        layer = if (state.recordingVideo) probe.distanceLayer else null,
        settings = settings,
        scene = sharedSceneOf(state, probe),
        trip = sharedTripOf(nav),
        photo = SharedCameraFrame::photoOf,
        pictos = { now -> sharedPictosOf(state.picto.hits, viewModel.picto::pngOf, now) },
    )
}

/** Une consigne ne devient jamais une affirmation qu'un mouvement a été effectué. */
internal fun sharedSceneOf(state: IseeUiState, probe: LiveProbeState): SharedScene? {
    if (state.recordingVideo && !probe.spoken.isNullOrBlank()) return SharedScene(probe.spoken)
    val entry = selectedEntry(state)
    if (entry == null || entry is ChatEntry.IntroBubble || entry.text.isBlank()) return null
    return SharedScene(entry.text, entry is ChatEntry.UserBubble, state.speakingEntryId == entry.id)
}
