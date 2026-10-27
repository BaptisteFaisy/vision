package dev.sam3vision.app.ihear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * L'écran d'accueil iSee : la scène du design, plein écran. Une grande bulle de verre
 * entourée de satellites porte le message courant (gros texte paginé sur 3 lignes,
 * libellé YOU / ISEE, texte bleu quand c'est la voix du user) ; la carte caméra prend
 * sa place pendant l'enregistrement, puis se dissout en pixels dans la bulle qui
 * grandit. Le message affiché est toujours le dernier : la navigation entre messages
 * (balayage vertical) a été supprimée — c'est [IseeScenePane] qui remplace l'ancienne
 * liste de bulles, il n'en reste qu'une à l'écran.
 *
 * Toute l'interaction passe par les gestes de `IhearRootLayout` ; le pane reste muet.
 */
@Composable
fun IhearHome(viewModel: IhearViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshSettings() }
    DisposableEffect(Unit) {
        viewModel.active = true
        onDispose { viewModel.active = false }
    }
    // Retour système : ouvre les Réglages (clés API, tests) sans bouton sur la surface.
    BackHandler(enabled = true) { onOpenSettings() }

    IseeScenePane(state = state, viewModel = viewModel, modifier = Modifier.fillMaxSize())
}