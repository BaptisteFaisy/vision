package dev.sam3vision.app.voicenav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.nav.RouteSummary

/**
 * Arrivée à destination : nom entendu + résumé du trajet effectué,
 * boutons « New destination » / « Close ».
 *
 * Gros texte malvoyant (thème sombre [AppColors]), même gabarit que
 * l'écran de confirmation : l'arrivée est dite à voix haute par la
 * session (« Arrived at destination »), l'écran sert de contrôle visuel.
 * [onNewDestination] rend la main à la dictée existante, [onClose]
 * referme (la route appelle `session().dismissArrived()`).
 */
@Composable
fun ArrivedScreen(
    state: VoiceNavUiState.Arrived,
    onNewDestination: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Arrival", color = AppColors.textMuted, fontSize = 18.sp)
        Text(
            state.destination,
            color = AppColors.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp,
        )
        ArrivedSummary(state.summary)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNewDestination,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.success),
        ) {
            Text("New destination", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Close", fontSize = 20.sp)
        }
    }
}

/** Rappel du trajet effectué : adresse (si connue) + « 2.4 km · 15 min ». */
@Composable
private fun ArrivedSummary(summary: RouteSummary?) {
    if (summary == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        summary.address?.let { address ->
            Text(address, color = AppColors.text, fontSize = 20.sp)
        }
        Text(
            "${summary.distance()} · ${summary.duration()}",
            color = AppColors.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
