package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.core.net.GpuStatus
import dev.sam3vision.core.settings.Settings

/** Door to the legacy screens, unreachable from the gesture-only iSee home. */
@Composable
internal fun ClassicScreens(
    onOpenLive: () -> Unit,
    onOpenClassic: () -> Unit,
    onOpenSegment: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Other screens",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        GlassButton("Direct mode (continuous video)", true, false, onOpenLive)
        GlassButton("Classic home (photo + analysis)", true, false, onOpenClassic)
        GlassButton("Segment demo (walkable area)", true, false, onOpenSegment)
    }
}

/** Sections USER + META GLASSES + TECHNICAL SETTINGS under the header. */
@Composable
internal fun SettingsBody(
    draft: Settings,
    onDraft: (Settings) -> Unit,
    glasses: GlassesController,
    llmConfigured: Boolean,
    speaking: SpeakState,
    gpu: GpuStatus,
    onSpeak: (String) -> Unit,
    onStopVoice: () -> Unit,
    onSave: (Settings) -> Unit,
    onTestLlm: () -> Unit,
    onTestSam: () -> Unit,
    onTestAsr: () -> Unit,
    onTestDepth: () -> Unit,
    onPickRole: (Settings) -> Unit,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
    nav: NavCallbacks,
    statusLine: String?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        UserSection(draft, onPickRole, onStartTracking, onStopTracking) { onDraft(it) }
        MetaGlassesSection(glasses = glasses)
        TechnicalSection(
            draft = draft,
            onChange = onDraft,
            llmConfigured = llmConfigured,
            speaking = speaking,
            gpu = gpu,
            onSpeak = onSpeak,
            onStopVoice = onStopVoice,
            onSave = onSave,
            onTestLlm = onTestLlm,
            onTestSam = onTestSam,
            onTestAsr = onTestAsr,
            onTestDepth = onTestDepth,
            onStartTracking = onStartTracking,
            onStopTracking = onStopTracking,
            nav = nav,
            statusLine = statusLine,
        )
    }
}
