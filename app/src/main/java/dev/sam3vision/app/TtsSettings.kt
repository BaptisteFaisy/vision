package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings

/**
 * "ElevenLabs voice" section of Settings: masked API key, voice ID and synthesis
 * test. The key stays encrypted in the store: it is never written into the code.
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
            "ElevenLabs voice (read aloud)",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        VoiceLanguageSettings(draft, onChange)
        LabeledField("ElevenLabs API key", draft.ttsApiKey, true) { onChange(draft.copy(ttsApiKey = it)) }
        LabeledField("ElevenLabs voice ID", draft.ttsVoiceId, false) { onChange(draft.copy(ttsVoiceId = it)) }
        SpeedSetting(draft, onChange)
        SpeakRow(
            state = speaking,
            text = testVoiceText(draft.speechLanguage),
            onSpeak = onSpeak,
            onStop = onStop,
            actionLabel = "Test voice",
            labelColor = SettingsLight.muted,
            buttonTextColor = SettingsLight.text,
            buttonBorderColor = SettingsLight.text,
        )
        VoiceSwitches(draft, onChange)
        IntroSetting(draft) { onChange(draft.copy(introEnabled = it)) }
    }
}

/**
 * Voice speed (×0.5 to ×2, ×1.9 by default): ±0.1 steps on the buttons, applied
 * in local playback — even cached audio speaks at the chosen speed.
 */
@Composable
private fun SpeedSetting(draft: Settings, onChange: (Settings) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Voice speed", color = SettingsLight.text, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(draft.copy(ttsSpeed = bumpTtsSpeed(draft.ttsSpeed, -0.1f))) }) {
                Text("−", fontSize = 16.sp, color = SettingsLight.text)
            }
            Text(formatTtsSpeed(draft.ttsSpeed), color = SettingsLight.text, fontSize = 13.sp)
            TextButton(onClick = { onChange(draft.copy(ttsSpeed = bumpTtsSpeed(draft.ttsSpeed, 0.1f))) }) {
                Text("+", fontSize = 16.sp, color = SettingsLight.text)
            }
        }
    }
}

/**
 * The playback switches: chat replies (🔊 button or automatic) and detected
 * position announcements. One explanation line under each.
 */
@Composable
private fun VoiceSwitches(draft: Settings, onChange: (Settings) -> Unit) {
    SwitchRow(
        label = "Read replies automatically",
        checked = draft.ttsAutoplay,
    ) { onChange(draft.copy(ttsAutoplay = it)) }
    SwitchRow(
        label = "Announce objects and their distance",
        checked = draft.ttsAnnouncePositions,
    ) { onChange(draft.copy(ttsAnnouncePositions = it)) }
    SwitchRow(
        label = "Guide straight ahead (iSee camera)",
        checked = draft.ttsGuidanceStraight,
    ) { onChange(draft.copy(ttsGuidanceStraight = it)) }
}

/** "Intro" switch + its explanation: the first message of the iSee chat. */
@Composable
private fun IntroSetting(draft: Settings, onChange: (Boolean) -> Unit) {
    SwitchRow(label = introToggleLabel(draft.introEnabled), checked = draft.introEnabled, onChange = onChange)
    Text(
        "Built-in intro (Laure voice, offline): played once at the first opening, " +
            "as the first chat message. It is cut with a long press by dictating " +
            "« Disable iSee intro ».",
        color = SettingsLight.muted,
        fontSize = 11.sp,
    )
}

/** "Label + switch" row: a single implementation for all switches (black/white, no blue). */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = SettingsLight.text, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = SettingsLight.text,
                checkedBorderColor = SettingsLight.text,
                uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                uncheckedTrackColor = SettingsLight.border,
                uncheckedBorderColor = SettingsLight.text,
            ),
        )
    }
}
