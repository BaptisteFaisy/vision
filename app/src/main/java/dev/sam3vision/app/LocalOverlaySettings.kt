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
 * "Local overlay" settings section: propagation of the last keyframe's mask
 * between two server responses (30 fps target, 4 ms paint budget).
 *
 * While no motion estimator is plugged in (L13 lane A), the overlay stays
 * **inert**: the switch is described as such, with no gain advertised.
 */
@Composable
internal fun LocalOverlaySettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Local overlay",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Local mask propagation", draft.localOverlayEnabled) {
            onChange(draft.copy(localOverlayEnabled = it))
        }
    }
}