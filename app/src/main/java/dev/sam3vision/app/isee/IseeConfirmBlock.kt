package dev.sam3vision.app.isee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.guidemap.GuidanceMapCard
import dev.sam3vision.app.voicenav.DestinationTextField
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.nav.initialGuidance

/**
 * La confirmation de destination sur l'accueil iSee, sous la caméra :
 * destination entendue, résumé d'itinéraire (ou chargement, ou motif),
 * carte d'aperçu du tracé, boutons Start / Cancel, rappel vocal et
 * saisie clavier de secours. La boule et la phrase en cours s'effacent
 * pendant la confirmation (comme en guidage), la caméra et la sonde
 * continuent, l'appui long reste une question libre et le tap la vidéo.
 *
 * Chaque contrôle publie sa zone ([navZone], exemption racine) : sans ça,
 * la racine intercepterait tout en tenue micro ou en bascule vidéo.
 */
@Composable
internal fun IseeConfirmBlock(
    state: VoiceNavUiState.Confirming,
    viewModel: IseeViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Destination", color = Color(IseePortrait.SUB), fontSize = 14.sp)
        Text(
            state.destination,
            color = Color(IseePortrait.INK),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 26.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ConfirmSummary(state)
        val summary = state.summary
        if (summary != null) {
            GuidanceMapCard(
                viewModel::confirmNavSnapshot,
                initialGuidance(summary),
                Modifier.fillMaxWidth().height(150.dp),
                showAttribution = false,
            )
        }
        ConfirmButtons(state, viewModel, Modifier.fillMaxWidth())
        Text(
            "Say “yes” or “no”",
            color = Color(IseePortrait.SUB),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        ConfirmTextCard(viewModel, Modifier.fillMaxWidth())
    }
}

/** Boutons Start / Cancel, côte à côte, zones publiées (exemption racine). */
@Composable
private fun ConfirmButtons(
    state: VoiceNavUiState.Confirming,
    viewModel: IseeViewModel,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = viewModel::confirmVoiceNavDestination,
            enabled = !state.loading,
            modifier = Modifier.weight(1f).height(52.dp)
                .navZone(CONFIRM_START_ID, viewModel.navButtons),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.success),
        ) {
            Text("Start", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = viewModel::cancelVoiceNavConfirmation,
            modifier = Modifier.weight(1f).height(52.dp)
                .navZone(CONFIRM_CANCEL_ID, viewModel.navButtons),
        ) {
            Text("Cancel", fontSize = 19.sp)
        }
    }
}

/** Saisie clavier de secours, sur carte sombre (champ au thème sombre). */
@Composable
private fun ConfirmTextCard(viewModel: IseeViewModel, modifier: Modifier = Modifier) {
    Box(
        modifier.navZone(CONFIRM_TEXT_ID, viewModel.navButtons)
            .background(AppColors.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        DestinationTextField(viewModel::submitTypedVoiceNavDestination)
    }
}

/** Résumé chargé, chargement en cours, ou motif du moteur — un seul visible. */
@Composable
private fun ConfirmSummary(state: VoiceNavUiState.Confirming) {
    when {
        state.loading -> LoadingRow()
        state.summary != null -> SummaryText(state.summary.address, state.summary.distance(), state.summary.duration())
        state.error != null -> Text(state.error, color = AppColors.danger, fontSize = 16.sp)
    }
}

/** Adresse (si connue) + « 2.4 km · 15 min », encre Portrait. */
@Composable
private fun SummaryText(address: String?, distance: String, duration: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        address?.let {
            Text(it, color = Color(IseePortrait.INK), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "$distance · $duration",
            color = AppColors.primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Indicateur + « Loading the route… » pendant l'appel moteur. */
@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = AppColors.primary, strokeWidth = 3.dp, modifier = Modifier.size(20.dp))
        Text("Loading the route…", color = Color(IseePortrait.SUB), fontSize = 16.sp)
    }
}
