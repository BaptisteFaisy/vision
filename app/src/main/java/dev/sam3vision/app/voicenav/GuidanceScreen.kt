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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.nav.progressOf

/**
 * Guidage actif : prochaine instruction en très gros, distance avant la
 * manœuvre, reste du trajet, barre de progression, bouton « Arrêter ».
 *
 * Les valeurs viennent du moteur d'itinéraire via
 * [VoiceNavViewModel.onGuidanceUpdate] — cet écran n'en calcule aucune, il
 * ne fait qu'afficher. [onVoiceEntry] redonne la main à la dictée existante
 * (« arrête le guidage » y est consommé comme ordre stop).
 */
@Composable
fun GuidanceScreen(
    state: VoiceNavUiState.Guiding,
    onStop: () -> Unit,
    onVoiceEntry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OffRouteBanner(state.update.offRoute)
        Text("Prochaine instruction", color = AppColors.textMuted, fontSize = 18.sp)
        Text(
            state.update.instruction,
            color = AppColors.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp,
        )
        if (state.summary == null && !state.update.arrived) {
            // Résumé inconnu (chargeur en échec, moteur parti quand même) :
            // « calcul en cours », jamais des zéros (« Dans 0 m »).
            Text("Calcul de l'itinéraire…", color = AppColors.textMuted, fontSize = 20.sp)
        } else {
            Text(
                state.update.maneuverFr(),
                color = AppColors.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(state.update.remainingFr(), color = AppColors.textMuted, fontSize = 20.sp)
        }
        GuidanceProgress(state)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger),
        ) {
            Text("Arrêter", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        VoiceStopButton(onVoiceEntry)
    }
}

/** Barre de progression du trajet (0 sans résumé initial connu). */
@Composable
private fun GuidanceProgress(state: VoiceNavUiState.Guiding) {
    val progress = state.summary?.let {
        progressOf(state.update.remainingDistanceMeters, it.distanceMeters)
    } ?: 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(12.dp),
        color = AppColors.success,
        trackColor = AppColors.surfaceHigh,
    )
}

/** « Dicter (“arrête le guidage”) » : retour à la dictée existante. */
@Composable
private fun VoiceStopButton(onVoiceEntry: () -> Unit) {
    TextButton(onClick = onVoiceEntry, modifier = Modifier.fillMaxWidth()) {
        Text("Dicter (« arrête le guidage »)", fontSize = 18.sp, color = AppColors.primary)
    }
}
