package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings

/**
 * Section « Debug map » : tracé schématique de l'itinéraire guidé + position
 * GPS en fond de l'écran iSee. Visible seulement pendant un guidage actif ;
 * sans guidage, l'écran est inchangé.
 */
@Composable
internal fun DebugMapSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Debug map",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Background route map (active guidance)", draft.debugRouteMapEnabled) {
            onChange(draft.copy(debugRouteMapEnabled = it))
        }
    }
}
