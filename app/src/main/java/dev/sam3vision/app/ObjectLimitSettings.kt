package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.settings.Settings

/**
 * Section « Objets à l'écran » : plafond commun au direct et à la photo
 * ([ObjectLimits.MIN_ON_SCREEN] à [ObjectLimits.MAX_ON_SCREEN]).
 * Deux gros boutons − / + plutôt qu'un curseur : visée et lecture faciles au doigt.
 */
@Composable
fun ObjectsSettings(draft: Settings, onChange: (Settings) -> Unit) {
    val current = draft.objectsOnScreen
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Objets à l'écran",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Au plus $current objet${if (current > 1) "s" else ""} — direct et photo. " +
                "Moins d'objets = moins de masques, direct plus fluide.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigButton(
                label = "−",
                enabled = current > ObjectLimits.MIN_ON_SCREEN,
                color = AppColors.surfaceHigh,
                onClick = { onChange(draft.copy(maxObjects = ObjectLimits.step(current, -1))) },
                widthFactor = 0.3f,
            )
            Text(
                "$current",
                color = AppColors.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            BigButton(
                label = "＋",
                enabled = current < ObjectLimits.MAX_ON_SCREEN,
                color = AppColors.surfaceHigh,
                onClick = { onChange(draft.copy(maxObjects = ObjectLimits.step(current, +1))) },
                widthFactor = 0.3f,
            )
        }
    }
}