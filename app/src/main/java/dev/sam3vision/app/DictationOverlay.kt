package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bandeau plein écran de la dictée : « J'écoute », « Transcription », puis le texte
 * en très gros caractères. La lecture à voix haute (ElevenLabs) est déclenchée par
 * le ViewModel ; TalkBack annonce aussi le bandeau comme région live ; un appui
 * n'importe où ferme le bandeau.
 */
@Composable
fun DictationOverlay(overlay: OverlayState, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM)
            .clickable(onClick = onDismiss)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = overlay.title.uppercase(),
                color = if (overlay.error) AppColors.danger else AppColors.success,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = overlay.message,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = overlay.spoken },
            )
            BigButton("Fermer", enabled = true, color = AppColors.primary, onClick = onDismiss)
        }
    }
}

private val SCRIM = Color.Black.copy(alpha = 0.96f)