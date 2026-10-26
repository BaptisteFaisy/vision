package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bloc « build » des Réglages : la version compilée et ce que **ce** build embarque.
 * Deux téléphones sur la même version peuvent se comporter différemment : le nom de
 * version ne décrit que le commit Git, pas les clés lues dans le `local.properties`
 * de chaque poste (hors dépôt) ni les réglages déjà enregistrés sur l'appareil.
 */
@Composable
internal fun BuildInfoBlock() {
    val summary = buildConfigSummary(
        llm = BuildConfig.LLM_API_KEY.isNotBlank(),
        voice = BuildConfig.TTS_API_KEY.isNotBlank(),
        sam = BuildConfig.SAM_BASE_URL.isNotBlank() || BuildConfig.SAM_US_BASE_URL.isNotBlank(),
        asr = BuildConfig.ASR_API_KEY.isNotBlank(),
    )
    Column(
        Modifier.fillMaxWidth()
            .background(AppColors.surfaceHigh, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(summary, color = AppColors.textMuted, fontSize = 11.sp)
    }
}

/** Résumé des clés injectées au build — jamais leur valeur, qui vient de `local.properties`. */
internal fun buildConfigSummary(llm: Boolean, voice: Boolean, sam: Boolean, asr: Boolean): String =
    "Clés du build — LLM : ${oui(llm)} · voix : ${oui(voice)} · dictée : ${oui(asr)} · SAM : ${oui(sam)}. " +
        "Elles remplissent à l'installation tout champ encore vide : rien à saisir. " +
        "Une valeur enregistrée sur ce téléphone l'emporte sur le build."

private fun oui(present: Boolean): String = if (present) "oui" else "non"
