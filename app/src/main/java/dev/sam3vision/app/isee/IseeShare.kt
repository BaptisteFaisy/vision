package dev.sam3vision.app.isee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.sam3vision.app.ShareRelay
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.SharedScene

/** Le proche reçoit les détections de cette caméra et la phrase courante de cet appareil. */
@Composable
internal fun IseeShare(state: IseeUiState, viewModel: IseeViewModel, settings: Settings) {
    val probe by viewModel.probe.state.collectAsState()
    ShareRelay(if (state.recordingVideo) probe.distanceLayer else null, settings, sharedSceneOf(state, probe))
}

/** Une consigne ne devient jamais une affirmation qu'un mouvement a été effectué. */
internal fun sharedSceneOf(state: IseeUiState, probe: LiveProbeState): SharedScene? {
    if (state.recordingVideo && !probe.spoken.isNullOrBlank()) return SharedScene(probe.spoken)
    val entry = selectedEntry(state)
    if (entry == null || entry is ChatEntry.IntroBubble || entry.text.isBlank()) return null
    return SharedScene(entry.text, entry is ChatEntry.UserBubble, state.speakingEntryId == entry.id)
}
