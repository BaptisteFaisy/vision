package dev.sam3vision.app.isee

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sam3vision.app.voicenav.RouteLoadingBlock
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.iseedesign.IseePortrait

/**
 * Le calcul d'itinéraire sur l'accueil iSee, sous la caméra : la boule
 * tourne pendant que le moteur cherche la route, la phrase en noir
 * dessous. Ni destination affichée, ni boutons, ni question vocale :
 * l'ordre GPS part directement en guidage (démarrage auto câblé).
 */
@Composable
internal fun IseeRouteLoading(
    state: VoiceNavUiState.Confirming,
    modifier: Modifier = Modifier,
) {
    RouteLoadingBlock(state, Color(IseePortrait.INK), modifier.fillMaxWidth())
}
