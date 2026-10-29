package dev.sam3vision.app.voicenav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.MapBounds
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.progressOf
import dev.sam3vision.core.settings.Settings

/**
 * Guidage actif : prochaine instruction en très gros, distance avant la
 * manœuvre, reste du trajet, petite carte (tracé + feux), barre de
 * progression, bouton « Arrêter ».
 *
 * Les valeurs viennent du moteur d'itinéraire via
 * [VoiceNavViewModel.onGuidanceUpdate] — cet écran n'en calcule aucune, il
 * ne fait qu'afficher. Tout l'écran est faisable à la voix
 * ([VoiceNavHoldButton], même moteur que l'accueil iSee) : ordre stop
 * (« arrête le guidage ») ou nouvelle destination — sans retour. Le micro
 * s'ouvre aussi par appui long n'importe où hors bouton ([HoldMicArea]).
 * La mini-carte n'apparaît que si [navSnapshot] et [fetchSignals] sont
 * fournis.
 */
@Composable
fun GuidanceScreen(
    state: VoiceNavUiState.Guiding,
    onStop: () -> Unit,
    hold: VoiceNavHold,
    settings: Settings,
    onHoldTranscript: suspend (String) -> Boolean,
    navSnapshot: (() -> Pair<NavRoute?, NavFix?>)? = null,
    fetchSignals: (suspend (MapBounds) -> List<RoutePoint>)? = null,
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
            hint = VoiceNavHoldText.GUIDING_NOT_UNDERSTOOD,
            ui = micUi,
            modifier = Modifier.weight(1f),
        ) {
            GuidingHead(state, navSnapshot, fetchSignals)
        }
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger),
        ) {
            Text("Arrêter", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        VoiceNavHoldButton(
            hold = hold,
            settings = settings,
            onTranscript = onHoldTranscript,
            hint = VoiceNavHoldText.GUIDING_NOT_UNDERSTOOD,
            ui = micUi,
        )
    }
}

/** Tête du guidage : instruction + carte + progression (zone d'appui long). */
@Composable
private fun GuidingHead(
    state: VoiceNavUiState.Guiding,
    navSnapshot: (() -> Pair<NavRoute?, NavFix?>)?,
    fetchSignals: (suspend (MapBounds) -> List<RoutePoint>)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OffRouteBanner(state.update.offRoute)
        Text("Prochaine instruction", color = AppColors.textMuted, fontSize = 18.sp)
        Text(
            state.update.instruction,
            color = AppColors.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp,
        )
        GuidanceSummary(state)
        if (navSnapshot != null && fetchSignals != null) {
            // Petite carte : où le guidage m'emmène (tracé + feux).
            MiniRouteMap(navSnapshot, fetchSignals, Modifier.fillMaxWidth().height(170.dp))
        }
        GuidanceProgress(state)
    }
}

/** Manœuvre + reste, ou « calcul en cours » (jamais des zéros). */
@Composable
private fun GuidanceSummary(state: VoiceNavUiState.Guiding) {
    if (state.summary == null && !state.update.arrived) {
        // Résumé inconnu (chargeur en échec, moteur parti quand même).
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

