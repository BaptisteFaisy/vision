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
 * "Build" block of Settings: the compiled version and what **this** build embeds.
 * Two phones on the same version may behave differently: the version name only
 * describes the Git commit, not the keys read from each machine's
 * `local.properties` (outside the repo) nor the settings already saved on device.
 */
@Composable
internal fun BuildInfoBlock() {
    Column(
        Modifier.fillMaxWidth()
            .background(SettingsLight.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            buildConfigSummary(
                llm = BuildConfig.LLM_API_KEY.isNotBlank(),
                voice = BuildConfig.TTS_API_KEY.isNotBlank(),
                sam = BuildConfig.SAM_BASE_URL.isNotBlank() ||
                    BuildConfig.SAM_US_BASE_URL.isNotBlank(),
                asr = BuildConfig.ASR_API_KEY.isNotBlank(),
            ),
            color = SettingsLight.muted,
            fontSize = 13.sp,
        )
    }
}

/** Summary of build-injected keys — never their value, which comes from `local.properties`. */
internal fun buildConfigSummary(llm: Boolean, voice: Boolean, sam: Boolean, asr: Boolean): String =
    "Build keys — LLM: ${yes(present = llm)} · voice: ${yes(present = voice)} · dictation: ${yes(present = asr)} · SAM: ${yes(present = sam)}. " +
        "They fill every still-empty field at install: nothing to type. " +
        "A value saved on this phone wins over the build."

private fun yes(present: Boolean): String = if (present) "yes" else "no"
