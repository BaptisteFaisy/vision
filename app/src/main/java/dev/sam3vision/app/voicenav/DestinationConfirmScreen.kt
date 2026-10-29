package dev.sam3vision.app.voicenav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.settings.Settings

/**
 * Confirmation de destination : adresse entendue + résumé d'itinéraire
 * (distance · durée), boutons « Démarrer » / « Annuler » / micro sur place.
 *
 * Gros texte malvoyant (thème sombre [AppColors]) : la question parlée est
 * posée à l'ouverture (« Do you really want to go to X? … »), et tout l'écran
 * est faisable à la voix ([VoiceNavHoldButton], même moteur que l'accueil
 * iSee) : « yes »/« no », nouvelle destination, ordre stop — sans retour.
 * Le micro s'ouvre aussi par appui long n'importe où hors boutons
 * ([HoldMicArea]) : pas besoin de viser le bouton. [onSubmitText] reçoit le
 * texte saisi au clavier ([DestinationTextField], câblé par l'intégrateur
 * vers `submitTypedDestination`) ; défaut vide = champ inopérant, l'appelant
 * historique compile inchangé.
 */
@Composable
fun DestinationConfirmScreen(
    state: VoiceNavUiState.Confirming,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    hold: VoiceNavHold,
    settings: Settings,
    onHoldTranscript: suspend (String) -> Boolean,
    onSubmitText: (String) -> Unit = {},
) {
    val micUi = rememberHoldMicUiState()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HoldMicArea(
            hold = hold,
            settings = settings,
            onTranscript = onHoldTranscript,
            hint = VoiceNavHoldText.CONFIRM_NOT_UNDERSTOOD,
            ui = micUi,
            modifier = Modifier.weight(1f),
        ) {
            ConfirmHead(state)
        }
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
        VoiceNavHoldButton(
            hold = hold,
            settings = settings,
            onTranscript = onHoldTranscript,
            hint = VoiceNavHoldText.CONFIRM_NOT_UNDERSTOOD,
            ui = micUi,
        )
        DestinationTextField(onSubmitText)
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

/** Tête de l'écran : destination entendue + résumé (zone d'appui long). */
@Composable
private fun ConfirmHead(state: VoiceNavUiState.Confirming) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Destination", color = AppColors.textMuted, fontSize = 18.sp)
        Text(
            state.destination,
            color = AppColors.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp,
        )
        SummaryBlock(state.summary, state.loading, state.error)
    }
}

