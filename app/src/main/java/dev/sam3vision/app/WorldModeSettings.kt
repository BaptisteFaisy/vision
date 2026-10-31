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
 * Section « World » des Réglages : l'interrupteur dehors/dedans câblé
 * sur [Settings.outdoorMode] (`outdoor_mode` en prefs).
 *
 * Dehors (défaut à l'installation) : la sonde suit les 12 dangers de la rue,
 * GPS autorisé ; dedans : elle suit les 7 objets d'intérieur, pas de GPS.
 * V1 : seuls les prompts SAM basculent, la portée reste 4 m.
 */
@Composable
internal fun WorldModeSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "World — outdoors / indoors",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Outdoors: street + GPS (off = indoors)", draft.outdoorMode) {
            onChange(draft.copy(outdoorMode = it))
        }
        Text(
            "Indoors: indoor objects, no GPS.",
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
    }
}
