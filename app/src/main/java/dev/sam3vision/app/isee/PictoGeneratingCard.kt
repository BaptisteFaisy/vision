package dev.sam3vision.app.isee

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Un tour de boule par 1,2 s : rotation lisible, moins pressée que la route. */
internal const val PICTO_SPIN_TURN_MS = 1200L

/** Texte du bloc pendant la génération (~10 s mesurées sur le téléphone). */
internal const val PICTO_GENERATING_TEXT = "Generating… (~10 s)"

/** Bloc semi-transparent : blanc à 78 %, le fond reste deviné. */
private val PICTO_GENERATING_BG = Color.White.copy(alpha = 0.78f)

/** Diamètre de la boule qui tourne sur le bloc. */
private val PICTO_ORB_SIZE = 56.dp

/** Niveau du liquide pendant la génération (marbre au repos). */
private const val PICTO_ORB_LEVEL = 0.45f

/**
 * L'attente de génération : un bloc fin semi-transparent au centre de la
 * scène, la boule iSee qui tourne (même motif que `RouteLoadingBlock`), le
 * texte en noir dessous — pas de titre, pas de rond bleu. Visible pendant
 * `Generating` seul, jamais dehors ([IseeScenePane] garde la condition).
 */
@Composable
internal fun PictoGeneratingCard(modifier: Modifier = Modifier) {
    val paint = rememberOrbPainter()
    val seconds = rememberPictoSpinSeconds()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 220.dp)
                .background(PICTO_GENERATING_BG, RoundedCornerShape(16.dp))
                .border(1.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.size(PICTO_ORB_SIZE)) {
                withTransform({ rotate(pictoSpinAngle(seconds), center) }) {
                    paint(this, center, size.minDimension / 2f, OrbLook(seconds, 0f, 0f, 0f, PICTO_ORB_LEVEL))
                }
            }
            Spacer(Modifier.height(8.dp))
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

/**
 * Angle de rotation en degrés pour [seconds] (zéro au départ, un tour par
 * [PICTO_SPIN_TURN_MS]). Pur : testé sur JVM.
 */
internal fun pictoSpinAngle(seconds: Float, turnMs: Long = PICTO_SPIN_TURN_MS): Float =
    ((seconds * 1000f) % turnMs.toFloat()) / turnMs.toFloat() * 360f

/**
 * Secondes écoulées sur une horloge monotone : avance toujours, même quand
 * les animations système sont coupées (`animator_duration_scale=0` fige
 * `rememberInfiniteTransition` — même motif que le temps de scène).
 */
@Composable
private fun rememberPictoSpinSeconds(): Float {
    var seconds by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            seconds = (SystemClock.elapsedRealtime() - start) / 1000f
            delay(16)
        }
    }
    return seconds
}
