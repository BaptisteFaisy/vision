package dev.sam3vision.app.isee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ce qu'on écrit quand iSee réfléchit sans avoir de message précis à montrer. */
private const val THINKING_LINE = "Thinking…"

/**
 * Le bas de l'écran : la ligne de statut (si elle a quelque chose à dire) posée
 * au-dessus de la pill d'instructions.
 *
 * La ligne existe parce que `IseeUiState.status` était écrit de partout (échec de
 * question, clé LLM absente, micro indisponible, rien entendu…) et lu par aucun
 * composable : l'écran restait identique à l'attente et l'utilisateur croyait que
 * iSee ne répondait pas.
 */
@Composable
internal fun SceneBottomBar(state: IseeUiState, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        statusLineOf(state)?.let { StatusLine(it, Modifier.padding(bottom = 8.dp)) }
        InstructionPill()
    }
}

/**
 * Le texte de la ligne de statut : le statut écrit par le view model s'il dit
 * quelque chose, sinon l'attente pendant la réflexion, sinon rien du tout.
 * Pure : même état, même ligne, testable sur JVM.
 */
internal fun statusLineOf(state: IseeUiState): String? {
    val status = state.status?.takeIf { it.isNotBlank() }
    return status ?: THINKING_LINE.takeIf { state.thinking }
}

/** Petit texte discret sur fond translucide arrondi : lisible sans crier. */
@Composable
internal fun StatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = IseeSceneColors.label,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(IseeSceneColors.instructBg.copy(alpha = 0.86f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** La pill d'instructions du bas : actions fortes sombres, verbes de service gris. */
@Composable
private fun InstructionPill(modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            val strong = SpanStyle(color = IseeSceneColors.instructStrong, fontWeight = FontWeight.Bold)
            val soft = SpanStyle(color = IseeSceneColors.instructGray)
            withStyle(strong) { append("Tap") }
            withStyle(soft) { append(" see") }
            withStyle(strong) { append("   Hold") }
            withStyle(soft) { append(" ask") }
        },
        color = IseeSceneColors.instructStrong,
        fontSize = 13.sp,
        modifier = modifier
            .background(IseeSceneColors.instructBg, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}