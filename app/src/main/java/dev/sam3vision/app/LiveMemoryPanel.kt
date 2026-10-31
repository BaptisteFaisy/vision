package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.live.TimelineText
import dev.sam3vision.core.model.SeenTrack
import kotlinx.coroutines.delay

/**
 * Panneau « mémoire du direct » : les objets suivis avec leur dernière vue, le
 * choix de l'instant (« il y a 5 s ») et la question qui porte sur ce journal.
 * L'horloge se rafraîchit toute seule : « il y a 3 s » reste vrai.
 */
@Composable
fun LiveMemoryPanel(
    memory: LiveMemoryState,
    onFocus: (Int) -> Unit,
    onAsk: (String) -> Unit,
    voice: LiveVoiceState,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val nowMs = rememberClock()
    Column(
        modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(AppColors.surface.copy(alpha = 0.94f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MemoryHeader(onClose)
        SeenChips(memory.tracks, nowMs)
        FocusChips(memory.focusSeconds, onFocus)
        AskRow(draft, { draft = it }, memory.asking, voice, onVoiceStart, onVoiceStop) {
            onAsk(draft)
            draft = ""
        }
        voice.error?.let { StatusBanner(it, AppColors.danger) }
        memory.answer?.let { AnswerBlock(it) }
        memory.error?.let { StatusBanner(it, AppColors.danger) }
    }
}

/** Titre du panneau et fermeture. */
@Composable
private fun MemoryHeader(onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Live memory", color = AppColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Chip("Close", AppColors.surfaceHigh, onClose)
    }
}

/** Objets suivis, du plus récent au plus ancien : « personne · il y a 3 s ». */
@Composable
private fun SeenChips(tracks: List<SeenTrack>, nowMs: Long) {
    if (tracks.isEmpty()) {
        Text("Nothing seen yet.", color = AppColors.textMuted, fontSize = 13.sp)
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tracks.sortedByDescending { it.lastAtMs }.take(MAX_CHIPS).forEach { track ->
            Chip(TimelineText.chipLabel(nowMs, track), AppColors.primary, null)
        }
    }
}

/** Instants proposés : maintenant, il y a 5 s, 15 s, 30 s. */
@Composable
private fun FocusChips(selected: Int, onFocus: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FOCUS_CHOICES.forEach { seconds ->
            val label = if (seconds == 0) "now" else "$seconds s ago"
            val color = if (seconds == selected) AppColors.success else AppColors.surfaceHigh
            Chip(label, color, onClick = { onFocus(seconds) })
        }
    }
}

/** Champ de question, envoi, et voix : on peut parler au lieu de taper. */
@Composable
private fun AskRow(
    draft: String,
    onChange: (String) -> Unit,
    asking: Boolean,
    voice: LiveVoiceState,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text("What was on the left 5 s ago?", color = AppColors.textMuted, fontSize = 13.sp) },
            modifier = Modifier.weight(1f),
        )
        VoiceChip(voice, asking, onVoiceStart, onVoiceStop)
        if (asking) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                color = AppColors.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Chip("Ask", AppColors.primary, onClick = { if (draft.isNotBlank()) onSend() })
        }
    }
}

/**
 * Pastille micro en appui maintenu : le micro s'ouvre à la pose du doigt et se
 * ferme au relâchement (« Stop » reste en secours). Grisée pendant une question
 * en cours ; indicateur pendant la transcription.
 */
@Composable
private fun VoiceChip(voice: LiveVoiceState, asking: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    when {
        voice.working -> CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = AppColors.primary,
            modifier = Modifier.size(24.dp),
        )
        voice.listening -> Chip("Stop", AppColors.danger, onStop)
        asking -> Chip("Speak", AppColors.surfaceHigh, null)
        else -> HoldChip(onStart, onStop)
    }
}

/** Pastille tenue : mêmes couleurs que [Chip], le relâchement termine la dictée. */
@Composable
private fun HoldChip(onStart: () -> Unit, onStop: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .background(AppColors.primary.copy(alpha = 0.9f), shape)
            .pointerInput(onStart, onStop) {
                detectTapGestures(onPress = {
                    onStart()
                    tryAwaitRelease()
                    onStop()
                })
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text("Speak", color = contentOn(AppColors.primary), fontSize = 13.sp, maxLines = 1)
    }
}

/** Réponse du LLM, sur le journal caméra. */
@Composable
private fun AnswerBlock(answer: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.surfaceHigh, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(answer, color = AppColors.text, fontSize = 14.sp)
    }
}

/** Horloge locale au panneau, rafraîchie toutes les secondes. */
@Composable
private fun rememberClock(periodMs: Long = 1_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Nombre de pastilles affichées au maximum (le reste reste dans le journal). */
private const val MAX_CHIPS = 8