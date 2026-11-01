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
 * Section « HAL post-processing » (TECHNICAL SETTINGS seulement) : allumé, la
 * liaison coupe les post-traitements du HAL (contours, bruit, shading, pixels
 * chauds) — mais seulement ceux que le capteur annonce, sinon repli défaut
 * silencieux (voir `CameraHalProcessing`). Éteint par défaut. Prend effet à la
 * prochaine liaison caméra : rouvrir l'écran caméra après sauvegarde.
 */
@Composable
internal fun CameraHalSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "HAL post-processing",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Bare HAL capture (if supported)", draft.cameraHalBareEnabled) {
            onChange(draft.copy(cameraHalBareEnabled = it))
        }
        Text(
            "Takes effect on next camera bind (reopen the camera screen). " +
                "Confirm with logcat: camhal: drapeau=true appliqué=edge+noise+shading+hot_pixel.",
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
    }
}
