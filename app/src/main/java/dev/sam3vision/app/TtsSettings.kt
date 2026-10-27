package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings

/**
 * Section « Voix ElevenLabs » des réglages : clé API (masquée), ID de voix et test de
 * synthèse. La clé reste chiffrée dans le store : elle n'est jamais écrite dans le code.
 */
@Composable
fun TtsSettings(
    draft: Settings,
    speaking: SpeakState,
    onChange: (Settings) -> Unit,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Voix ElevenLabs (lecture à voix haute)",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        VoiceLanguageSettings(draft, onChange)
        LabeledField("Clé API ElevenLabs", draft.ttsApiKey, true) { onChange(draft.copy(ttsApiKey = it)) }
        LabeledField("ID de voix ElevenLabs", draft.ttsVoiceId, false) { onChange(draft.copy(ttsVoiceId = it)) }
        Text(
            "Voix premade : Sarah EXAVITQu4vr4xnSDxMaL · George JBFqnCBsd6RMkjVDRZzb. " +
                "Jade US ${Settings.JADE_US_VOICE_ID} (celle de l'intro) est une voix de " +
                "bibliothèque : elle répond 402 sans crédits.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        SpeakRow(
            state = speaking,
            text = testVoiceText(draft.speechLanguage),
            onSpeak = onSpeak,
            onStop = onStop,
            actionLabel = "Tester la voix",
        )
        VoiceSwitches(draft, onChange)
        IntroSetting(draft) { onChange(draft.copy(introEnabled = it)) }
    }
}

/**
 * Les deux bascules de lecture : réponses du chat (bouton 🔊 ou automatique) et
 * annonce des positions détectées. Une ligne d'explication sous chacune.
 */
@Composable
private fun VoiceSwitches(draft: Settings, onChange: (Settings) -> Unit) {
    SwitchRow(
        label = "Lire les réponses automatiquement",
        checked = draft.ttsAutoplay,
    ) { onChange(draft.copy(ttsAutoplay = it)) }
    Text(
        "Activé : chaque réponse du chat est lue dès qu'elle arrive. Désactivé : bouton 🔊 seulement.",
        color = AppColors.textMuted,
        fontSize = 11.sp,
    )
    SwitchRow(
        label = "Annoncer les objets et leur distance",
        checked = draft.ttsAnnouncePositions,
    ) { onChange(draft.copy(ttsAnnouncePositions = it)) }
    Text(
        "Activé : en mode Direct (et après l'analyse d'une photo), la voix dit où sont les " +
            "objets et à quelle distance — « Personne à gauche, à 1,4 mètre ; chien au " +
            "centre, à 3 mètres ». Une annonce toutes les 4 s au plus, et seulement si la " +
            "scène change : un objet qui s'approche d'un mètre est ré-annoncé.",
        color = AppColors.textMuted,
        fontSize = 11.sp,
    )
    SwitchRow(
        label = "Guider tout droit (caméra iSee)",
        checked = draft.ttsGuidanceStraight,
    ) { onChange(draft.copy(ttsGuidanceStraight = it)) }
    Text(
        "Activé : la page caméra dit la consigne de marche au lieu des positions — " +
            "« Continue tout droit. » ou « Personne à 2 mètres devant, va à gauche. ». " +
            "Calculé sur le téléphone depuis SAM, sans appel LLM ; un obstacle qui entre " +
            "dans le couloir parle aussitôt, même avant les 4 s.",
        color = AppColors.textMuted,
        fontSize = 11.sp,
    )
}

/** Bascule « intro » + son explication : le premier message du chat de iSee. */
@Composable
private fun IntroSetting(draft: Settings, onChange: (Boolean) -> Unit) {
    SwitchRow(label = introToggleLabel(draft.introEnabled), checked = draft.introEnabled, onChange = onChange)
    Text(
        "Intro embarquée (voix Jade US, hors ligne) : jouée une seule fois à la première " +
            "ouverture, comme premier message du chat. On la coupe à l'appui long en " +
            "dictant : « Disable iSee intro ».",
        color = AppColors.textMuted,
        fontSize = 11.sp,
    )
}

/** Ligne « libellé + interrupteur » : une seule implémentation pour toutes les bascules. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = AppColors.text, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
