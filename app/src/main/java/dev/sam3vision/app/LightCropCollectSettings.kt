package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings

/**
 * "Light crop collection" settings section (TECHNICAL SETTINGS only) : when
 * enabled, every classified pedestrian-light crop (≤ 48 px PNG, box and
 * verdict) is appended to `lightcrops-*.jsonl` in the app's private files,
 * for hand-labeling and retraining (see `docs/COLLECTE-FEUX.md`). Crops
 * only, never the full scene : no faces, no plates. Off by default.
 */
@Composable
internal fun LightCropCollectSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Light crop collection",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        SwitchRow("Collect light crops (2000/session max)", draft.collectLightCrops) {
            onChange(draft.copy(collectLightCrops = it))
        }
        Text(
            "Crops only (≤ 48 px), private files, pull with phone-adb. See docs/COLLECTE-FEUX.md.",
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
    }
}
