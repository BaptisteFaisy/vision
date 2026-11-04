package dev.sam3vision.app.voicenav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors

/**
 * Le calcul d'itinéraire : destination seule, sans animation ni phrase
 * d'attente (demande user 2026-11-04 — ni « Finding », ni boule qui
 * tourne) ; le motif d'erreur à la place en cas d'échec. Partagé par
 * l'accueil iSee et l'écran navigation (même moment, deux thèmes).
 */
@Composable
fun RouteLoadingBlock(
    state: VoiceNavUiState.Confirming,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val message = state.error ?: state.destination
        Text(
            message,
            color = if (state.error != null) AppColors.danger else textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
