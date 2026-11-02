package dev.sam3vision.app.isee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.picto.PictoPlaceMode

/** Ce qu'on écrit quand iSee réfléchit sans avoir de message précis à montrer. */
private const val THINKING_LINE = "Thinking…"

/**
 * Le bas de l'écran : le témoin de lieu, persistant, puis la ligne de statut
 * (si elle a quelque chose à dire) — la pill « Tap see / Hold ask » est retirée.
 *
 * La ligne existe parce que `IseeUiState.status` était écrit de partout (échec de
 * question, clé LLM absente, micro indisponible, rien entendu…) et lu par aucun
 * composable : l'écran restait identique à l'attente et l'utilisateur croyait que
 * iSee ne répondait pas.
 */
@Composable
internal fun SceneBottomBar(state: IseeUiState, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlaceChip(placeLabelOf(state.placeMode), Modifier.padding(bottom = 6.dp))
        statusLineOf(state)?.let { StatusLine(it) }
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

/**
 * Témoin de lieu : le mot « INDOOR »/« OUTDOOR », en anglais comme le
 * reste de l'interface. Pur : même lieu, même mot, testable sur JVM.
 */
internal fun placeLabelOf(placeMode: PictoPlaceMode): String =
    if (placeMode.pictoEnabled) "INDOOR" else "OUTDOOR"

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

/** Pastille de lieu, persistante : le statut défile, le lieu reste. */
@Composable
private fun PlaceChip(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = IseeSceneColors.label,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(IseeSceneColors.instructBg.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
