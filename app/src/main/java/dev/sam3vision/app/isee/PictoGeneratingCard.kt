package dev.sam3vision.app.isee

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Texte du bloc pendant la génération (~10 s mesurées sur le téléphone). */
internal const val PICTO_GENERATING_TEXT = "Generating… (~10 s)"

/** Bloc semi-transparent : blanc à 78 %, le fond reste deviné. */
private val PICTO_GENERATING_BG = Color.White.copy(alpha = 0.78f)

/**
 * L'attente de génération : un bloc fin semi-transparent au centre de la
 * scène, le texte en noir — pas de titre, pas de rond bleu, pas de boule
 * qui tourne. Visible pendant `Generating` seul, jamais dehors
 * ([IseeScenePane] garde la condition).
 */
@Composable
internal fun PictoGeneratingCard(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 220.dp)
                .background(PICTO_GENERATING_BG, RoundedCornerShape(16.dp))
                .border(1.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                PICTO_GENERATING_TEXT,
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
