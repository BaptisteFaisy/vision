package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import java.util.Locale

/**
 * "Distances" settings section: Direct overlay switch and fallback field of
 * view (`0` = straight sensor reading, the normal path).
 */
@Composable
internal fun DistanceSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Distances",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        SwitchRow("Distances on Direct", draft.distanceOverlayEnabled) {
            onChange(draft.copy(distanceOverlayEnabled = it))
        }
        LabeledField(
            label = "Camera field of view (°, 0 = sensor reading)",
            value = fovText(draft.cameraFovDeg),
            secret = false,
        ) { text -> onChange(draft.copy(cameraFovDeg = parseFov(text))) }
    }
}

/** Displayed field-of-view value: empty when the sensor decides. */
internal fun fovText(deg: Float): String =
    if (deg > 0f) String.format(Locale.US, "%.1f", deg).removeSuffix(".0") else ""

/** Tolerant field-of-view input: comma accepted, [LensSpec] bounds, 0 = auto. */
internal fun parseFov(text: String): Float {
    val value = text.replace(',', '.').trim().toFloatOrNull() ?: return 0f
    return if (value <= 0f) 0f else value.coerceIn(LensSpec.FOV_MIN, LensSpec.FOV_MAX)
}