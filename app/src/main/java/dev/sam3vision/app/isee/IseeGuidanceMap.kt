package dev.sam3vision.app.isee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.guidemap.GuidanceMapCard
import dev.sam3vision.app.voicenav.OffRouteBanner
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute

/**
 * Le guidage GPS sur l'accueil iSee, sous la caméra et tout en bas de
 * l'écran : carte réduite (tracé + position, repli schéma si les tuiles
 * échouent) et instruction en cours, sans le reste du trajet (ni km ni
 * temps). La boule reste affichée entre la caméra et la carte — sauf
 * quand le user parle : son transcript prend alors la place de la boule
 * au milieu ([showGuidanceTranscript]). La caméra et la sonde continuent
 * (évitement d'obstacles préservé). L'arrêt se fait à la voix
 * (« arrête le guidage »).
 *
 * Affichée par `IseeScenePane` quand la session est en
 * [VoiceNavUiState.Guiding] ; rien sans tracé (la carte ne se compose pas,
 * l'instruction reste).
 */
@Composable
internal fun IseeGuidanceBlock(
    state: VoiceNavUiState.Guiding,
    snapshot: () -> Pair<NavRoute?, NavFix?>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OffRouteBanner(state.update.offRoute, Modifier.fillMaxWidth())
        GuidanceMapCard(
            snapshot,
            state.update,
            Modifier.fillMaxWidth().height(160.dp),
            showAttribution = false,
        )
        GuidanceInstruction(state)
    }
}

/** Instruction en cours, en grand et encre Portrait (sans le reste : ni km ni temps). */
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
}

/**
 * Tout en bas de l'écran : la navigation quand elle a un objectif (calcul
 * d'itinéraire, ou guidage avec carte suivie), sinon la boule et la
 * phrase ([orb]). Pendant le guidage, la boule reste affichée entre la
 * caméra et la carte, sauf quand le user parle (son transcript prend sa
 * place, voir [showGuidanceTranscript]). Le choix vit ici (état de
 * session observé) pour garder `PortraitScene` sous le plafond de 50
 * lignes. La barre du bas s'efface pendant le guidage
 * ([isGuidanceOverlay]) : pas de recouvrement.
 */
@Composable
internal fun BoxWithConstraintsScope.SceneGuidanceLayer(
    viewModel: IseeViewModel,
    orb: @Composable () -> Unit,
) {
    val navState by viewModel.voiceNavState.collectAsState()
    val placed = Modifier.align(Alignment.BottomCenter).padding(bottom = navInsetDp() + 10.dp).padding(horizontal = 16.dp)
    when (val nav = navState) {
        is VoiceNavUiState.Guiding -> {
            // La boule reste entre la caméra et la carte (la carte passe par-dessus).
            orb()
            IseeGuidanceBlock(nav, viewModel::debugNavSnapshot, placed)
        }
        is VoiceNavUiState.Confirming -> IseeRouteLoading(nav, placed)
        else -> orb()
    }
}

/**
 * Le guidage recouvre-t-il la scène (calcul d'itinéraire ou carte suivie) :
 * la carte prend le bas de l'écran et la barre du bas s'efface (la boule
 * et la phrase restent). Pure : même état, même réponse, testée sur JVM.
 */
internal fun isGuidanceOverlay(navState: VoiceNavUiState): Boolean =
    navState is VoiceNavUiState.Guiding || navState is VoiceNavUiState.Confirming

/**
 * En guidage (caméra en haut, carte en bas), le transcript du user prend-il
 * la place de la boule au milieu : oui quand le user parle avec un
 * transcript à afficher, sinon la boule reste. Hors guidage : jamais (la
 * phrase reste sous la boule). Pure : même état, même réponse, testée
 * sur JVM.
 */
internal fun showGuidanceTranscript(navState: VoiceNavUiState, state: IseeUiState): Boolean =
    navState is VoiceNavUiState.Guiding &&
        state.holding && state.userSpeaking && state.liveTranscript.isNotBlank()
