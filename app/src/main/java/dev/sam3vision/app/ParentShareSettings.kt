package dev.sam3vision.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.delay

/**
 * "Parent tracking": the link sent over WhatsApp, then permanent tracking.
 * The stream id is not editable — each phone gets its own on first open —,
 * so nothing needs renaming before sharing.
 */
@Composable
internal fun ParentShareSettings(
    draft: Settings,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
    onChange: (Settings) -> Unit,
) {
    val trackingActive by ShareTracking.running.collectAsState()
    val shareProblem by ShareHealth.message.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Parent tracking",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LabeledField("Tracking relay URL", draft.parentSiteUrl, false) {
            onChange(draft.copy(parentSiteUrl = it))
        }
        TrackingLink(draft)
        SwitchRow("Send position + objects to the site during Direct", draft.shareEnabled) {
            onChange(draft.copy(shareEnabled = it))
        }
        // Switch off: the link exists but no send goes out, not a word.
        if (!draft.shareEnabled) {
            Text(
                "Sharing is off: no relative can follow this phone, even with the right link.",
                color = SettingsLight.danger,
                fontSize = 12.sp,
            )
        }
        LabeledField("Relay write key (generated, never shared)", draft.shareWriteKey, true) {
            onChange(draft.copy(shareWriteKey = it))
        }
        // Tracking may fail silently: relay refusal, unreachable relay or
        // cut location all read here, never into the void.
        shareProblem?.let {
            Text(it, color = SettingsLight.danger, fontSize = 12.sp)
        }
        if (trackingActive) {
            GlassButton("Stop tracking", true, false, onStopTracking)
        } else {
            GlassButton("\uD83D\uDCE4 Share tracking", true, false, onClick = { onStartTracking(draft) })
        }
    }
}

/**
 * The exact link leaving over WhatsApp, readable **and** copyable from Settings.
 * Without this, the conversation was the only place to read it: no way to check
 * which stream a phone publishes, so a link gone stale (reinstalled app, new
 * id) looked exactly like a loved one who has not filmed yet.
 */
@Composable
private fun TrackingLink(draft: Settings) {
    val context = LocalContext.current
    val link = shareLink(draft.parentSiteUrl, draft.shareId)
    var copied by remember(link) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Tracking link sent to the relative", color = SettingsLight.muted, fontSize = 12.sp)
        SelectionContainer {
            Text(
                link.ifBlank { "No link yet: reload the app once." },
                color = SettingsLight.text,
                fontSize = 12.sp,
            )
        }
        GlassButton(if (copied) "✓ Link copied" else "Copy link", link.isNotBlank(), false, onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("iSee tracking link", link))
            copied = clipboard != null
        })
        Text(
            "Open this link yourself to check the tracking. The link changes if iSee is reinstalled.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
    }
}
