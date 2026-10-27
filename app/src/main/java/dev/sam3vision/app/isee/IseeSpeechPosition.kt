package dev.sam3vision.app.isee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.sam3vision.core.iseedesign.SpeechPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Suit le lecteur, y compris sa préparation et la vitesse ×0,5 à ×2. */
@Composable
internal fun rememberSpeechPosition(viewModel: IseeViewModel, entryId: Long?): SpeechPosition? {
    var position by remember(entryId) { mutableStateOf<SpeechPosition?>(null) }
    LaunchedEffect(entryId) {
        if (entryId == null) return@LaunchedEffect
        while (isActive) {
            position = viewModel.speechPosition()
            delay(40)
        }
    }
    return position
}
