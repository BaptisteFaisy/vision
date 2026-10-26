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
 * Section « Langue de la voix » des Réglages : la personne parle **français** ou
 * **anglais**.
 *
 * Ce que la langue change : les annonces des objets et de leur distance, les réponses
 * du chat (elles sont lues telles quelles) et la consigne donnée au modèle. Ce qu'elle
 * ne change pas : l'interface, qui reste en français.
 */
@Composable
fun VoiceLanguageSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Langue de la voix",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SpeechLanguage.entries.forEach { language ->
            val active = draft.speechLanguage == language
            BigButton(
                label = language.label,
                enabled = true,
                color = if (active) AppColors.primary else AppColors.surfaceHigh,
                onClick = { onChange(pickVoiceLanguage(draft, language)) },
            )
        }
        Text(
            "La personne parle dans cette langue : annonces des objets et de leur " +
                "distance, réponses du chat lues à voix haute. L'interface, elle, reste " +
                "en français.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

/** Choix de la langue de la voix : un seul endroit qui écrit le réglage. */
internal fun pickVoiceLanguage(draft: Settings, language: SpeechLanguage): Settings =
    draft.copy(speechLanguage = language)
