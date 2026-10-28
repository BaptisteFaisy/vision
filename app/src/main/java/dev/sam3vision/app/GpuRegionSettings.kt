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
import dev.sam3vision.core.net.GpuPresence
import dev.sam3vision.core.net.GpuStatus
import dev.sam3vision.core.settings.SamRegion
import dev.sam3vision.core.settings.Settings

/**
 * "SAM GPU — region" section of Settings: Europe mode (eu) or **US mode** (us).
 * Only changes the endpoint being called; powering the card stays an explicit
 * decision (`~/bin/sam3-gpu on` / `~/bin/sam3-gpu-us on`), never two GPUs at once.
 * The status line shows the observed presence: the phone notices by itself when the
 * card is plugged in while this page is open (bounded watch, [GpuPresence]).
 */
@Composable
fun GpuRegionSettings(draft: Settings, gpu: GpuStatus, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "SAM 3.1 GPU — region",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        RegionButtons(draft, onChange)
        Text(
            gpuStatusLine(gpu),
            color = SettingsLight.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val down = gpu.presence as? GpuPresence.Down
        if (down != null) {
            Text(down.reason, color = SettingsLight.muted, fontSize = 11.sp)
        }
    }
}

/**
 * GPU presence line (the page is in English) : connected, checking, unreachable with
 * its reason on the next line, or unknown until the first observed call. Pure, JVM-tested.
 */
internal fun gpuStatusLine(status: GpuStatus): String = when (status.presence) {
    GpuPresence.Up -> "GPU status: connected ✓"
    GpuPresence.Checking -> "GPU status: checking…"
    is GpuPresence.Down -> "GPU status: unreachable."
    GpuPresence.Unknown -> "GPU status: unknown — run “Test SAM”."
}

/**
 * One **full-width** button per region, the active region in full color.
 * Measured on the phone: side by side (0.47), the label was cut as
 * "US · États-" — unreadable for a setting that costs money.
 */
@Composable
private fun RegionButtons(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SamRegion.entries.forEach { region ->
            val active = draft.samRegion == region
            GlassButton(
                label = region.label,
                enabled = true,
                primary = active,
                onClick = { onChange(pickRegion(draft, region)) },
            )
        }
    }
}

/**
 * Choosing a region clears a SAM URL that was only a region preset (injected by
 * the build or inherited from an older version): without that, it would keep
 * winning over the choice. A hand-typed URL is kept.
 */
internal fun pickRegion(draft: Settings, region: SamRegion): Settings = draft.copy(
    samRegion = region,
    samBaseUrl = if (SamRegion.isPreset(draft.samBaseUrl)) "" else draft.samBaseUrl,
)