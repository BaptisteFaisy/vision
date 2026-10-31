package dev.sam3vision.app.voicenav

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.isee.OrbLook
import dev.sam3vision.app.isee.rememberOrbPainter
import kotlinx.coroutines.delay

/** Un tour de boule par 800 ms : rotation rapide et lisible. */
internal const val ROUTE_SPIN_TURN_MS = 800L

/** Diamètre de la boule pendant le calcul. */
private val ROUTE_ORB_SIZE = 120.dp

/** Niveau du liquide pendant le calcul (marbre au repos). */
private const val ROUTE_ORB_LEVEL = 0.45f

/** Phrase du calcul d'itinéraire (anglais, exigence produit). */
internal const val FINDING_ROUTE_TEXT = "Finding the best route"

/**
 * Le calcul d'itinéraire : la boule de verre iSee tourne vite pendant
 * que le moteur cherche la route, la phrase dessous ([textColor], noire
 * sur l'accueil), le motif d'erreur à la place en cas d'échec. Partagé
 * par l'accueil iSee et l'écran navigation (même moment, deux thèmes).
 */
@Composable
fun RouteLoadingBlock(
    state: VoiceNavUiState.Confirming,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val paint = rememberOrbPainter()
    val seconds = rememberSpinSeconds()
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(ROUTE_ORB_SIZE)) {
            withTransform({ rotate(spinAngle(seconds), center) }) {
                paint(this, center, size.minDimension / 2f, OrbLook(seconds, 0f, 0f, 0f, ROUTE_ORB_LEVEL))
            }
        }
        Spacer(Modifier.height(12.dp))
        val message = state.error ?: FINDING_ROUTE_TEXT
        Text(
            message,
            color = if (state.error != null) AppColors.danger else textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Angle de rotation en degrés pour [seconds] (zéro au départ, un tour par
 * [ROUTE_SPIN_TURN_MS]). Pur : testé sur JVM.
 */
internal fun spinAngle(seconds: Float, turnMs: Long = ROUTE_SPIN_TURN_MS): Float =
    ((seconds * 1000f) % turnMs.toFloat()) / turnMs.toFloat() * 360f

/**
 * Secondes écoulées sur une horloge monotone : avance toujours, même quand
 * les animations système sont coupées (`animator_duration_scale=0` fige
 * `rememberInfiniteTransition` — même motif que le temps de scène).
 */
@Composable
private fun rememberSpinSeconds(): Float {
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
