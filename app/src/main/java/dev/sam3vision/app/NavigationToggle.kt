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
 * Section « Navigation » des Réglages : l'interrupteur « guidage GPS » câblé
 * sur [Settings.navigationGuidanceEnabled] (`navigation_guidance` en prefs),
 * le champ URL Valhalla et la ligne d'état FR ([routeStatusText]).
 *
 * Oui (défaut à l'installation) : la destination confirmée part en guidage
 * (in-app si l'URL Valhalla est renseignée, sinon intent `google.navigation:`
 * puis repli URL universelle) ; non : seule la fiche du lieu s'affiche,
 * sans guidage lancé.
 */
@Composable
internal fun NavigationToggle(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Navigation — guidage GPS",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Guide to the confirmed destination", draft.navigationGuidanceEnabled) {
            onChange(draft.copy(navigationGuidanceEnabled = it))
        }
        LabeledField("URL Valhalla (vide = guidage Google Maps uniquement)", draft.routeBaseUrl, false) {
            onChange(draft.copy(routeBaseUrl = it.trim()))
        }
        Text(
            routeStatusText(draft.routeBaseUrl, draft.navigationGuidanceEnabled),
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
    }
}
