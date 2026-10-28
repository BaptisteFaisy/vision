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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.nav.RouteSummary

/**
 * Confirmation de destination : adresse entendue + résumé d'itinéraire
 * (distance · durée), boutons « Démarrer » / « Annuler » / dictée.
 *
 * Gros texte malvoyant (thème sombre [AppColors]) : la destination est lue
 * à voix haute à l'ouverture (« Destination : X »), l'écran sert de
 * contrôle visuel avant le départ. [onVoiceEntry] redonne la main à la
 * dictée existante (l'intégrateur déclenche le pipeline habituel).
 */
@Composable
fun DestinationConfirmScreen(
    state: VoiceNavUiState.Confirming,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onVoiceEntry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Destination", color = AppColors.textMuted, fontSize = 18.sp)
        Text(
            state.destination,
            color = AppColors.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp,
        )
        SummaryBlock(state.summary, state.loading, state.error)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onConfirm,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.success),
        ) {
            Text("Démarrer", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Annuler", fontSize = 20.sp)
        }
        RedictateButton(onVoiceEntry)
    }
}

/** Résumé chargé, chargement en cours, ou erreur du moteur — un seul visible. */
@Composable
private fun SummaryBlock(summary: RouteSummary?, loading: Boolean, error: String?) {
    when {
        loading -> LoadingRow()
        summary != null -> SummaryText(summary)
        error != null -> Text(error, color = AppColors.danger, fontSize = 20.sp)
    }
}

/** Adresse (si connue) + « 2,4 km · 15 min ». */
@Composable
private fun SummaryText(summary: RouteSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        summary.address?.let { address ->
            Text(address, color = AppColors.text, fontSize = 20.sp)
        }
        Text(
            "${summary.distanceFr()} · ${summary.durationFr()}",
            color = AppColors.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Indicateur + « Chargement de l'itinéraire… » pendant l'appel moteur. */
@Composable
private fun LoadingRow() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(color = AppColors.primary)
        Text("Chargement de l'itinéraire…", color = AppColors.textMuted, fontSize = 18.sp)
    }
}

/** « Dicter une autre destination » : retour à la dictée existante. */
@Composable
private fun RedictateButton(onVoiceEntry: () -> Unit) {
    TextButton(onClick = onVoiceEntry, modifier = Modifier.fillMaxWidth()) {
        Text("Dicter une autre destination", fontSize = 18.sp, color = AppColors.primary)
    }
}
