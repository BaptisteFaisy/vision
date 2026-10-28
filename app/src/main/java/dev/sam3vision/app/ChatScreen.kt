package dev.sam3vision.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role

/** Chat sur la photo courante : historique visible, question écrite ou dictée, réponse lisible à voix haute. */
@Composable
fun ChatScreen(
    state: UiState,
    onBack: () -> Unit,
    onAsk: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onStopVoice: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.dictation.seq) {
        if (state.dictation.text.isNotBlank()) draft = mergeDraft(draft, state.dictation.text)
    }
    val send: () -> Unit = {
        val question = draft.trim()
        if (question.isNotEmpty() && !state.asking) {
            onAsk(question)
            draft = ""
        }
    }
    Column(Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding().navigationBarsPadding()) {
        ScreenHeader("Questions sur la photo", null, onBack, null)
        if (state.chat.isEmpty() && !state.asking) {
            StatusBanner("Pose ta question sur la photo — le modèle répond à partir de l'image.", AppColors.surfaceHigh)
        }
        MessageList(state, onSpeak, onStopVoice, Modifier.weight(1f))
        state.statusLine?.let { StatusBanner(it, AppColors.warning) }
        ChatComposer(
            state = state,
            draft = draft,
            onDraft = { draft = it },
            onSend = send,
            onStartDictation = onStartDictation,
            onStopDictation = onStopDictation,
        )
    }
}

/** Concatène une dictée au brouillon existant sans coller les mots. */
fun mergeDraft(draft: String, dictated: String): String =
    listOf(draft.trim(), dictated.trim()).filter { it.isNotEmpty() }.joinToString(" ")

/** Fil des bulles + indicateur d'attente. */
@Composable
private fun MessageList(
    state: UiState,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        reverseLayout = false,
    ) {
        items(state.chat) { turn -> ChatBubble(turn, state.speak, onSpeak, onStop) }
        if (state.asking) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 3.dp,
                        color = AppColors.primary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

/** Champ de saisie + bouton « Dicter » (Meta, repli Scribe) + envoi. */
@Composable
private fun ChatComposer(
    state: UiState,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
) {
    val context = LocalContext.current
    // Refus comme accord : sans micro, onStartDictation() échoue proprement.
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onStartDictation()
    }
    val toggleMic: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        when {
            state.dictation.recording -> onStopDictation()
            granted -> onStartDictation()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text("Ex. : qu'y a-t-il à gauche ?", color = AppColors.textMuted, fontSize = 14.sp) },
            singleLine = true,
            enabled = !state.asking,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
        )
        DictationButton(state.dictation, toggleMic)
        TextButton(onClick = onSend, enabled = draft.isNotBlank() && !state.asking) {
            Text("Envoyer", fontSize = 15.sp)
        }
    }
}

/** Libellé selon l'état : « Dicter », « Stop », ou points de suspension pendant la transcription. */
@Composable
private fun DictationButton(dictation: DictationState, onClick: () -> Unit) {
    val label = when {
        dictation.busy -> "…"
        dictation.recording -> "Stop"
        else -> "Dicter"
    }
    TextButton(onClick = onClick, enabled = !dictation.busy) {
        Text(label, fontSize = 15.sp, color = if (dictation.recording) AppColors.danger else AppColors.primary)
    }
}

/** Bulle de conversation, alignée selon le rôle ; une réponse de l'assistant s'écoute. */
@Composable
private fun ChatBubble(
    turn: ChatTurn,
    speaking: SpeakState,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit,
) {
    val fromUser = turn.role == Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(Modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                turn.content,
                color = if (fromUser) ColorWhite else AppColors.text,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(if (fromUser) AppColors.primary else AppColors.surface, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            )
            if (!fromUser) SpeakRow(state = speaking, text = turn.content, onSpeak = onSpeak, onStop = onStop)
        }
    }
}

private val ColorWhite = androidx.compose.ui.graphics.Color.White
