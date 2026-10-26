package dev.sam3vision.app.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.BigButton
import dev.sam3vision.app.StatusBanner

/**
 * Bloc « Lunettes » des Réglages : état lisible et trois gestes utiles
 * (enregistrer auprès de Meta AI, démarrer la session, prendre une photo).
 * Les lunettes simulées n'apparaissent qu'en build debug.
 */
@Composable
fun GlassesSection(
    state: GlassesUiState,
    debugBuild: Boolean,
    onRegister: () -> Unit,
    onConnect: () -> Unit,
    onCapture: () -> Unit,
    onDisconnect: () -> Unit,
    onStartMock: () -> Unit,
    onMockPress: () -> Unit,
    onStopMock: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Lunettes Ray-Ban Meta", color = AppColors.text, fontSize = 15.sp)
        GlassesStatusLines(state)
        state.message?.let { StatusBanner(it.text, bannerColor(it.kind)) }

        BigButton(
            label = if (state.registered) "Ré-enregistrer auprès de Meta AI" else "Enregistrer auprès de Meta AI",
            enabled = state.sdkReady,
            color = AppColors.primary,
            onClick = onRegister,
        )
        BigButton(
            label = connectLabel(state),
            enabled = state.connected || state.canConnect,
            color = AppColors.surfaceHigh,
            onClick = { if (state.connected) onDisconnect() else onConnect() },
        )
        BigButton(
            label = "Prendre une photo",
            enabled = state.connected,
            color = AppColors.surfaceHigh,
            onClick = onCapture,
        )
        photoHint(state)?.let { Text(it, color = AppColors.warning, fontSize = 12.sp) }
        if (debugBuild) {
            MockControls(state, onStartMock, onMockPress, onStopMock)
        }
    }
}

/** La gravité colore le bandeau : un échec ne se lit pas comme un simple conseil. */
private fun bannerColor(kind: MessageKind): Color = when (kind) {
    MessageKind.ERROR -> AppColors.danger
    MessageKind.WARNING -> AppColors.warning
    MessageKind.INFO -> AppColors.surfaceHigh
}

/** État technique en quatre lignes, en français lisible. */
@Composable
private fun GlassesStatusLines(state: GlassesUiState) {
    statusLines(state).forEach { Text(it, color = AppColors.textMuted, fontSize = 12.sp) }
}

/** Commandes de simulation, visibles uniquement en debug. */
@Composable
private fun MockControls(
    state: GlassesUiState,
    onStartMock: () -> Unit,
    onMockPress: () -> Unit,
    onStopMock: () -> Unit,
) {
    Text("Démo sans matériel", color = AppColors.text, fontSize = 13.sp)
    if (!state.mockActive) {
        BigButton("Simuler des lunettes", true, AppColors.surfaceHigh, onStartMock)
    } else {
        BigButton("Simuler un appui sur le bouton capture", state.connected, AppColors.surfaceHigh, onMockPress)
        BigButton("Retirer les lunettes simulées", true, AppColors.surfaceHigh, onStopMock)
    }
}