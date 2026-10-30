package dev.sam3vision.app.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.GlassButton
import dev.sam3vision.app.SettingsLight
import dev.sam3vision.app.StatusBanner

/**
 * Entrée du banc d'essai lunettes, fournie par la navigation d'iSee
 * (`AppNav` pousse `Route.Bench`). `null` = pas d'hôte de navigation (aperçu,
 * test) : le bouton disparaît, et ce bloc n'a pas à connaître `AppNav`.
 */
val LocalBenchOpener = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Bloc « Lunettes » des Réglages : état lisible et trois gestes utiles
 * (enregistrer auprès de Meta AI, démarrer la session, prendre une photo),
 * puis l'entrée du banc d'essai ([LocalBenchOpener]).
 * Les lunettes simulées n'apparaissent qu'en build debug.
 */
@Composable
fun GlassesSection(
    state: GlassesUiState,
    debugBuild: Boolean,
    onRegister: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestMic: () -> Unit,
    onConnect: () -> Unit,
    onCapture: () -> Unit,
    onDisconnect: () -> Unit,
    onStartMock: () -> Unit,
    onMockPress: () -> Unit,
    onMockHold: () -> Unit,
    onMockVoiceLaunch: () -> Unit,
    onStopMock: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Lunettes Ray-Ban Meta", color = SettingsLight.text, fontSize = 15.sp)
        GlassesStatusLines(state)
        state.message?.let { StatusBanner(it.text, bannerColor(it.kind)) }
        PermissionActions(onRequestCamera, onRequestMic)
        ProductionActions(state, onRegister, onConnect, onCapture, onDisconnect)
        photoHint(state)?.let { Text(it, color = AppColors.warning, fontSize = 12.sp) }
        talkHint(state)?.let { Text(it, color = AppColors.warning, fontSize = 12.sp) }
        if (debugBuild) {
            MockControls(state, onStartMock, onMockPress, onMockHold, onMockVoiceLaunch, onStopMock)
        }
        BenchEntry()
    }
}

/**
 * Autorisations des lunettes : la question se pose dans **Meta AI**, pas dans les réglages du
 * téléphone ni dans ceux d'iSee. Ces deux boutons ouvrent la page du SDK, la seule voie qui
 * aboutit — en Developer Mode la fiche de l'app n'expose que « Déconnecter ».
 *
 * Toujours affichés, même quand le SDK n'est pas encore prêt : le premier appui appelle
 * `initialize()`. Les masquer derrière `sdkReady` cachait le **seul** chemin qui autorise la
 * caméra, ce qui se lisait comme « il n'y a aucun bouton ».
 */
@Composable
private fun PermissionActions(onCamera: () -> Unit, onMic: () -> Unit) {
    GlassButton("Autoriser la caméra (Meta AI)", true, primary = false, onClick = onCamera)
    GlassButton("Autoriser le micro (Meta AI)", true, primary = false, onClick = onMic)
}

/** Les trois gestes de production : enregistrement Meta AI, session, photo. */
@Composable
private fun ProductionActions(
    state: GlassesUiState,
    onRegister: () -> Unit,
    onConnect: () -> Unit,
    onCapture: () -> Unit,
    onDisconnect: () -> Unit,
) {
    GlassButton(
        label = if (state.registered) "Ré-enregistrer auprès de Meta AI" else "Enregistrer auprès de Meta AI",
        enabled = state.sdkReady,
        primary = false,
        onClick = onRegister,
    )
    GlassButton(
        label = connectLabel(state),
        enabled = state.connected || state.canConnect,
        primary = false,
        onClick = { if (state.connected) onDisconnect() else onConnect() },
    )
    GlassButton(
        label = "Prendre une photo",
        enabled = state.connected,
        primary = false,
        onClick = onCapture,
    )
}

/** Entrée du banc d'essai lunettes : émise seulement si la navigation la fournit. */
@Composable
private fun BenchEntry() {
    val openBench = LocalBenchOpener.current ?: return
    GlassButton("Banc d'essai lunettes", true, false, openBench)
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
    statusLines(state).forEach { Text(it, color = SettingsLight.muted, fontSize = 12.sp) }
}

/** Commandes de simulation, visibles uniquement en debug. */
@Composable
private fun MockControls(
    state: GlassesUiState,
    onStartMock: () -> Unit,
    onMockPress: () -> Unit,
    onMockHold: () -> Unit,
    onMockVoiceLaunch: () -> Unit,
    onStopMock: () -> Unit,
) {
    Text("Démo sans matériel", color = SettingsLight.text, fontSize = 13.sp)
    if (!state.mockActive) {
        GlassButton("Simuler des lunettes", true, false, onStartMock)
    } else {
        GlassButton("Simuler un appui sur le bouton capture", state.connected, false, onMockPress)
        GlassButton("Simuler un appui long (parler)", state.connected, false, onMockHold)
        GlassButton("Simuler ouvre iSee (voix)", state.mockActive, false, onMockVoiceLaunch)
        GlassButton("Retirer les lunettes simulées", true, false, onStopMock)
    }
}