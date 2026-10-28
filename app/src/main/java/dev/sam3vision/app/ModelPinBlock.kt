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
 * « Modèles effectifs » des Réglages : noms exacts des 2 modèles + bases + statuts de
 * clés (la clé LLM n'est « chauffée » qu'après un « Tester le LLM » OK) +
 * avertissement si le build impose un autre modèle que l'épinglé. Lecture seule : le
 * brouillon vaut le réglage seedé à l'ouverture, il bouge ensuite avec la saisie.
 * Textes calculés par [modelPinLines] (pur, testé en JVM).
 */
@Composable
internal fun ModelPinBlock(draft: Settings, statusLine: String?) {
    val testLine = statusLine?.takeIf { it.startsWith("LLM ") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Modèles effectifs",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        modelPinLines(draft, BuildConfig.LLM_MODEL, testLine).forEach { line ->
            Text(line, color = SettingsLight.muted, fontSize = 11.sp)
        }
    }
}
