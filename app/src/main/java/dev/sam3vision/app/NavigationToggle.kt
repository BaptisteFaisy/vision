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
 * "Navigation" settings section: the "GPS guidance" switch wired to
 * [Settings.navigationGuidanceEnabled] (`navigation_guidance` in prefs).
 *
 * On (fresh install default): the confirmed destination goes to guidance
 * (`google.navigation:` intent, then universal URL fallback); off: only the
 * place card shows, with no guidance started.
 */
@Composable
internal fun NavigationToggle(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Navigation — GPS guidance",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Guide to the confirmed destination", draft.navigationGuidanceEnabled) {
            onChange(draft.copy(navigationGuidanceEnabled = it))
        }
        LabeledField("Route base URL (Valhalla, empty = Maps guidance only)", draft.routeBaseUrl, false) {
            onChange(draft.copy(routeBaseUrl = it.trim()))
        }
    }
}
