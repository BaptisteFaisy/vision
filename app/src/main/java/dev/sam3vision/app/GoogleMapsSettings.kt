package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings

/** Le champ de la carte est dans les paramètres, jamais sous la carte. */
@Composable
internal fun GoogleMapsSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Maps", color = SettingsLight.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LabeledField("Google Maps API key", draft.googleMapsApiKey, true) {
            onChange(draft.copy(googleMapsApiKey = it.trim()))
        }
        Text(
            if (draft.googleMapsApiKey.isBlank()) "The build key is filled in at installation."
            else "Key filled in: Google Maps is selected for the caregiver map.",
            color = SettingsLight.muted, fontSize = 11.sp,
        )
    }
}
