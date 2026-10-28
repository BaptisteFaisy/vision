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
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * "Voice language" section of Settings: the person speaks **French** or **English**.
 *
 * What the language changes: object and distance announcements, chat replies
 * (read as-is) and the instruction given to the model. What it does not change:
 * anything else.
 */
@Composable
fun VoiceLanguageSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Voice language",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SpeechLanguage.entries.forEach { language ->
            val active = draft.speechLanguage == language
            GlassButton(
                label = language.label,
                enabled = true,
                primary = active,
                onClick = { onChange(pickVoiceLanguage(draft, language)) },
            )
        }
    }
}

/** Voice language choice: a single place that writes the setting. */
internal fun pickVoiceLanguage(draft: Settings, language: SpeechLanguage): Settings =
    draft.copy(speechLanguage = language)
