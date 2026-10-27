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
 * Section « Calque local » des réglages : la propagation du masque du dernier keyframe
 * entre deux réponses du serveur (30 ips visés, budget de peinture 4 ms).
 *
 * Tant que l'estimateur de mouvement n'est pas branché (lane A de L13), le calque reste
 * **inerte** : la bascule est donc décrite comme telle, sans annoncer un gain.
 */
@Composable
internal fun LocalOverlaySettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Calque local",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Propagation locale du masque", draft.localOverlayEnabled) {
            onChange(draft.copy(localOverlayEnabled = it))
        }
        Text(
            "Entre deux keyframes du serveur, le masque est redessiné sur le téléphone à " +
                "30 ips. Sans estimateur de mouvement, le calque reste inerte et l'écran " +
                "ne change pas.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}