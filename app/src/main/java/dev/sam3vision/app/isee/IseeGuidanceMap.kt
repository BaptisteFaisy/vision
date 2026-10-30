package dev.sam3vision.app.isee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.guidemap.GuidanceMapCard
import dev.sam3vision.app.voicenav.OffRouteBanner
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute

/**
 * Le guidage GPS sur l'accueil iSee, sous la caméra : carte (tracé +
 * position, repli schéma si les tuiles échouent), instruction en cours,
 * reste du trajet et bouton d'arrêt. La boule et la phrase en cours
 * s'effacent pendant le guidage (la carte prend leur place), la caméra
 * et la sonde continuent (évitement d'obstacles préservé).
 *
 * Affichée par `IseeScenePane` quand la session est en
 * [VoiceNavUiState.Guiding] ; rien sans tracé (la carte ne se compose pas,
 * l'instruction reste). Le bouton d'arrêt publie sa zone ([navZone],
 * exemption racine), sinon intouchable.
 */
@Composable
internal fun IseeGuidanceBlock(
    state: VoiceNavUiState.Guiding,
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    onStop: () -> Unit,
    zones: NavButtonZones,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OffRouteBanner(state.update.offRoute, Modifier.fillMaxWidth())
        GuidanceMapCard(snapshot, state.update, Modifier.fillMaxWidth().height(150.dp))
        GuidanceInstruction(state)
        StopGuidanceButton(onStop, zones, Modifier.fillMaxWidth())
    }
}

/** Instruction en cours + reste, en grand et encre Portrait (jamais des zéros). */
@Composable
private fun GuidanceInstruction(state: VoiceNavUiState.Guiding) {
    Text(
        state.update.instruction,
        color = Color(IseePortrait.INK),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 24.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        remainingLine(state),
        color = Color(IseePortrait.SUB),
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** « Dans 50 m · Encore 2,4 km · 12 min », ou « calcul en cours » sans résumé. */
private fun remainingLine(state: VoiceNavUiState.Guiding): String =
    if (state.summary == null && !state.update.arrived) {
        "Calcul de l'itinéraire…"
    } else {
        "${state.update.maneuverFr()} · ${state.update.remainingFr()}"
    }

/** Bouton d'arrêt : même rouge danger que l'écran de guidage historique. */
@Composable
private fun StopGuidanceButton(onStop: () -> Unit, zones: NavButtonZones, modifier: Modifier = Modifier) {
    Button(
        onClick = onStop,
        modifier = modifier.navZone(NAV_STOP_ID, zones).height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger),
    ) {
        Text("Arrêter le guidage", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Sous la caméra : la navigation quand elle a un objectif (confirmation
 * avec carte d'aperçu, ou guidage avec carte suivie), sinon la boule et la
 * phrase ([orb]). Le choix vit ici (état de session observé) pour garder
 * `PortraitScene` sous le plafond de 50 lignes : l'appelant ne fait que
 * passer la hauteur de la rue ([pictureH]).
 */
@Composable
internal fun BoxWithConstraintsScope.SceneGuidanceLayer(
    viewModel: IseeViewModel,
    open: Float,
    pictureH: Dp,
    orb: @Composable () -> Unit,
) {
    val navState by viewModel.voiceNavState.collectAsState()
    val mapTop = if (open > 0.5f) pictureH + 8.dp else 96.dp
    val placed = Modifier.align(Alignment.TopCenter).padding(top = mapTop).padding(horizontal = 16.dp)
    when (val nav = navState) {
        is VoiceNavUiState.Guiding -> IseeGuidanceBlock(
            nav,
            viewModel::debugNavSnapshot,
            viewModel::dismissVoiceNav,
            viewModel.navButtons,
            placed,
        )
        is VoiceNavUiState.Confirming -> IseeConfirmBlock(nav, viewModel, placed)
        else -> orb()
    }
}
