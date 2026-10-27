package dev.sam3vision.app.isee

import dev.sam3vision.app.ISEE_INTRO_TEXT
import dev.sam3vision.core.iseedesign.SpeechPosition
import dev.sam3vision.core.iseedesign.SpokenCaption
import dev.sam3vision.core.iseedesign.spokenCaption

/** Le texte est celui de la voix en cours, jamais celui d'une sélection périmée. */
internal fun currentCaption(state: IseeUiState, position: SpeechPosition?): SpokenCaption? = when {
    state.holding -> if (state.userSpeaking) spokenCaption(state.liveTranscript, null, live = true) else null
    state.speakingEntryId == null -> null
    else -> spokenCaption(currentSpeechText(state), position)
}

private fun currentSpeechText(state: IseeUiState): String = when (state.speakingEntryId) {
    INTRO_BUBBLE_ID -> ISEE_INTRO_TEXT
    else -> state.entries.firstOrNull { it.id == state.speakingEntryId }?.text ?: state.speakingText
}
