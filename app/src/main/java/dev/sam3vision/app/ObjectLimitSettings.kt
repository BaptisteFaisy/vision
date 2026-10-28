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
 * "On-screen objects" section: cap shared by Direct and photo
 * ([ObjectLimits.MIN_ON_SCREEN] to [ObjectLimits.MAX_ON_SCREEN]).
 * Two big − / + buttons instead of a slider: easy to aim and read by finger.
 */
@Composable
fun ObjectsSettings(draft: Settings, onChange: (Settings) -> Unit) {
    val current = draft.objectsOnScreen
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "On-screen objects",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton(
                label = "−",
                enabled = current > ObjectLimits.MIN_ON_SCREEN,
                primary = false,
                onClick = { onChange(draft.copy(maxObjects = ObjectLimits.step(current, -1))) },
                widthFactor = 0.3f,
            )
            Text(
                "$current",
                color = SettingsLight.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            GlassButton(
                label = "+",
                enabled = current < ObjectLimits.MAX_ON_SCREEN,
                primary = false,
                onClick = { onChange(draft.copy(maxObjects = ObjectLimits.step(current, +1))) },
                widthFactor = 0.3f,
            )
        }
    }
}