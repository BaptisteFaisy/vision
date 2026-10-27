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
 * Section « Navigation » des réglages : l'interrupteur « Guidage GPS » branché
 * sur [Settings.navigationGuidanceEnabled] (`navigation_guidance` en prefs).
 *
 * Allumé (défaut d'une installation fraîche) : la destination confirmée part en
 * guidage (intent `google.navigation:` puis repli URL universelle) ; éteint :
 * seule la fiche du lieu s'affiche, sans lancer de guidage.
 */
@Composable
internal fun NavigationToggle(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Navigation — guidage GPS",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Guider vers la destination confirmée", draft.navigationGuidanceEnabled) {
            onChange(draft.copy(navigationGuidanceEnabled = it))
        }
        Text(
            "« Emmène-moi à la gare » ouvre Google Maps en guidage. Éteint, seule " +
                "la fiche du lieu s'affiche, sans lancer de guidage.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}
