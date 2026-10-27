package dev.sam3vision.app.isee

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.sam3vision.core.settings.Settings

/**
 * L'accueil Portrait : fond blanc, une seule boule de verre et la phrase courante.
 * La caméra se fond en haut pendant la capture ; aucun historique ni satellite.
 * Le partage relaie les objets et le texte vers le lien reçu par le caregiver.
 *
 * Toute l'interaction passe par les gestes de `IseeRootLayout` ; le pane reste muet.
 */
@Composable
fun IseeHome(viewModel: IseeViewModel, onOpenSettings: () -> Unit, settings: Settings) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshSettings() }
    DisposableEffect(Unit) {
        viewModel.active = true
        onDispose { viewModel.active = false }
    }
    // Retour système : ouvre les Réglages (clés API, tests) sans bouton sur la surface.
    BackHandler(enabled = true) { onOpenSettings() }
    IseeShare(state, viewModel, settings)

    IseeScenePane(state = state, viewModel = viewModel, modifier = Modifier.fillMaxSize())
}
