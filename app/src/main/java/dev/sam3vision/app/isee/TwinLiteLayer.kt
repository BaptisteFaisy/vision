package dev.sam3vision.app.isee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

/** Tout droit : le même vert que la flèche du guidage. */
private val TWINLITE_FREE = Color(0xFF2ECC71)

/** Biais conseillé : le même ambre que le cap bouché. */
private val TWINLITE_BIAS = Color(0xFFF1C40F)

/** Stop : le même rouge que la Traversée. */
private val TWINLITE_STOP = Color(0xFFE74C3C)

/**
 * Flèche TwinLite sur la vidéo : bas-centrée comme le guidage, avec son libellé —
 * verte tout droit, ambre sur un biais, rouge à l'arrêt. Posée après [SteeringLayer] ;
 * rien de composé sans masque frais (caméra coupée, moteur en chargement).
 */
@Composable
internal fun TwinLiteLayer(ui: TwinLiteUi?, modifier: Modifier = Modifier) {
    if (ui == null) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = LAYER_BOTTOM_DP.dp),
        ) {
            Text(
                ui.arrow,
                color = twinLiteColor(ui.arrow),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                ui.label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Couleur du glyphe (mêmes glyphes que [TwinLiteDirection.arrow][dev.sam3vision.core.segment.TwinLiteDirection.arrow]). */
private fun twinLiteColor(arrow: String): Color = when (arrow) {
    "↑" -> TWINLITE_FREE
    "■" -> TWINLITE_STOP
    else -> TWINLITE_BIAS
}

/** Bas de carte, au-dessus de la pastille Traversée. */
private const val LAYER_BOTTOM_DP = 28
