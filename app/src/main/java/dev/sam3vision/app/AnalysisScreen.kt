package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Résultat d'analyse : photo + masques, description du LLM, objets, accès au chat. */
@Composable
fun AnalysisScreen(
    state: UiState,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onToggleMasks: () -> Unit,
    onRetry: () -> Unit,
    onSpeak: (String) -> Unit,
    onStopVoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Analysis",
            subtitle = state.analysis?.tags?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            onBack = onBack,
            onSettings = onOpenSettings,
        )
        state.photo?.let { PhotoStage(it, state.overlays, state.showMasks) }
        state.segmentationNote?.let { StatusBanner(it, AppColors.warning) }
        PhaseBlock(state.phase, onRetry)
        state.analysis?.description?.let { DescriptionCard(it, state.speak, onSpeak, onStopVoice) }
        state.timingsLine.takeIf { it.isNotBlank() }?.let { TimingsLine(it) }
        ObjectList(state.objects)
        AnalysisActions(state, onOpenChat, onToggleMasks)
    }
}

/** Le bloc central dépend de l'étape : chargement, échec + réessai, ou rien. */
@Composable
private fun PhaseBlock(phase: Phase, onRetry: () -> Unit) {
    when (phase) {
        // Idle = écran poussé, chargement pas encore fini : le spinner répond au clic en 1 frame.
        Phase.Analyzing, Phase.Idle -> Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator(color = AppColors.primary) }

        is Phase.Failed -> Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusBanner(phase.message, AppColors.danger)
            BigButton("Retry", true, AppColors.danger, onRetry)
        }

        else -> Unit
    }
}

/** Boutons d'action : question au modèle et bascule des masques. */
@Composable
private fun AnalysisActions(state: UiState, onOpenChat: () -> Unit, onToggleMasks: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigButton("Ask a question", state.hasPhoto, AppColors.primary, onOpenChat, 0.62f)
        BigButton(
            if (state.showMasks) "Masks on" else "Masks off",
            state.overlays.isNotEmpty(),
            AppColors.surfaceHigh,
            onToggleMasks,
            0.38f,
        )
    }
}

/** Chronologie de l'analyse sur une ligne (même lecture que le direct). */
@Composable
private fun TimingsLine(line: String) {
    Text(line, color = AppColors.success, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp))
}

/** Bloc de description produit par le LLM, avec lecture à voix haute (ElevenLabs). */
@Composable
private fun DescriptionCard(
    description: String,
    speaking: SpeakState,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(AppColors.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(description, color = AppColors.text, fontSize = 15.sp, fontWeight = FontWeight.Normal)
        SpeakRow(state = speaking, text = description, onSpeak = onSpeak, onStop = onStop)
    }
}
