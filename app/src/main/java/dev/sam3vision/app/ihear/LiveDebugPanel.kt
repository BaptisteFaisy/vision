package dev.sam3vision.app.ihear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fond du panneau : sombre et translucide, lisible sur une image claire comme sur une sombre. */
private val PanelBackground = Color(0xD010161C)
private val PanelDetail = Color(0xFFAFC0CC)

/** Chronologie : un ton au-dessus du détail, elle se lit d'un coup d'œil pendant le direct. */
private val PanelTimings = Color(0xFF7FD8A8)

/**
 * Panneau de debug de SAM sur la page caméra : la ligne d'état (« SAM · 1,2 ips ·
 * 640 ms · 8 objets · A100 »), la **chronologie sur une ligne** (« latence 348 · capt 22 ·
 * up 61 · srv 92 · rtt 153 · masque 37 · pixel 8 ») puis le détail utile quand quelque
 * chose cloche — endpoint, taille de frame, p95, poste par poste, étages serveur, nombre
 * de frames et de masques, dernière erreur.
 *
 * Purement informatif : aucun clic (les gestes de la page sont captés à la racine).
 */
@Composable
fun LiveDebugPanel(
    state: LiveProbeState,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    if (!state.debugVisible || state.idle) return
    // En mode dense (fenêtre caméra, un peu moins d'un demi-écran), le panneau ne garde
    // que la ligne d'état et une éventuelle erreur : le détail complet masquait l'aperçu
    // et les masques, c'est-à-dire exactement ce qu'on vient regarder.
    val details = if (dense) {
        panelDetails(state, dense).filter { it.startsWith(ERROR_PREFIX) }
    } else {
        panelDetails(state, dense)
    }
    Column(
        modifier
            .then(if (dense) Modifier else Modifier.statusBarsPadding())
            .padding(
                horizontal = if (dense) 4.dp else 12.dp,
                vertical = if (dense) 6.dp else 34.dp,
            )
            .background(PanelBackground, RoundedCornerShape(if (dense) 8.dp else 12.dp))
            .padding(horizontal = if (dense) 6.dp else 12.dp, vertical = if (dense) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) 1.dp else 2.dp),
    ) {
        Text(
            liveProbeHeadline(state),
            color = Color.White,
            fontSize = if (dense) 9.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = if (dense) 2 else Int.MAX_VALUE,
        )
        // Chronologie : une seule ligne, jamais de retour à la ligne dans l'aperçu.
        // En mode dense elle reste visible — c'est la fenêtre que l'on regarde quand on
        // mesure la latence —, mais le détail complet de `master` y est réduit aux erreurs.
        val chronologie = liveProbeTimingsLine(state)
        if (chronologie.isNotBlank()) {
            Text(
                chronologie,
                color = PanelTimings,
                fontSize = if (dense) 8.sp else 11.sp,
                maxLines = 1,
            )
        }
        details.forEach { line ->
            Text(line, color = PanelDetail, fontSize = if (dense) 8.sp else 11.sp, maxLines = 1)
        }
    }
}

/** Préfixe des lignes d'erreur de [liveProbeDetails] : les seules gardées en mode dense. */
private const val ERROR_PREFIX = "erreur"

/**
 * Lignes du détail. En mode [dense] (fenêtre caméra flottante, ~500 px de large), on
 * garde ce qui se lit d'un coup d'œil : latence, distances, phrase dite, erreur,
 * concepts — l'endpoint complet n'y tiendrait pas.
 */
private fun panelDetails(state: LiveProbeState, dense: Boolean): List<String> {
    val lines = liveProbeDetails(state)
    if (!dense) return lines
    // En dense, on garde ce qui se lit d'un coup d'œil : la fenêtre est petite et
    // l'endpoint, la taille de frame et le nom du GPU n'y apprennent rien de plus.
    return lines.filterNot { line ->
        line.startsWith("endpoint") || line.startsWith("frame :") || line.startsWith("gpu :")
    }
}