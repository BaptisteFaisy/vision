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
 * « Parent tracking » : le lien envoyé par WhatsApp, puis le suivi permanent.
 * L'identifiant de flux n'est pas modifiable — chaque téléphone reçoit le sien à la
 * première ouverture —, donc rien n'est à renommer avant de partager.
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
        // Interrupteur coupé : le lien existe mais aucun envoi ne part, sans un mot.
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
        // Le suivi échoue peut-être sans rien dire : refus du relais, relais
        // injoignable ou localisation coupée se lisent ici, jamais dans le vide.
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
 * Le lien exact qui part par WhatsApp, lisible **et** copiable depuis les Réglages.
 * Sans ça, la conversation était le seul endroit où le lire : impossible de vérifier
 * quel flux un téléphone publie, donc un lien devenu faux (app réinstallée, nouvel
 * identifiant) ressemblait trait pour trait à un proche qui n'a pas encore filmé.
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
