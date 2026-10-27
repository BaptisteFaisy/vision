package dev.sam3vision.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Page Caregiver : la carte de suivi du proche, telle que la page du site la
 * dessine (point et objets sur la carte, badge LIVE / SIGNAL LOST / OFFLINE,
 * bandeau sous la carte, zoom au pincement et boutons +/−).
 *
 * C'est **la même page** que celle envoyée par WhatsApp, tenue dans une WebView :
 * la carte et ses gestes sont ceux du site, donc la page évolue sans reconstruire
 * l'app, et aucun SDK de cartes n'entre dans le téléphone. [link] est le lien du
 * flux (`…?share=<identifiant>`), construit par [shareLink].
 */
@Composable
fun CaregiverScreen(link: String, onBack: () -> Unit) {
    val context = LocalContext.current
    // La WebView est créée pendant la composition, jamais dans le `factory` d'AndroidView :
    // y toucher à l'état de composition la faisait détruire aussitôt (fenêtre noire).
    val web = remember(link) { if (link.isBlank()) null else caregiverWeb(context, link) }
    DisposableEffect(web) {
        onDispose {
            web?.stopLoading()
            web?.destroy()
        }
    }
    Column(
        Modifier.fillMaxSize().background(AppColors.background)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        CaregiverBar(onBack = onBack, onReload = { web?.reload() })
        if (web == null) CaregiverMissingLink() else CaregiverWeb(web, Modifier.fillMaxWidth().weight(1f))
    }
}

/** Barre de la page : retour à l'app et rafraîchissement de la carte. */
@Composable
private fun CaregiverBar(onBack: () -> Unit, onReload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onBack) { Text("Retour", fontSize = 13.sp) }
        Text(
            "Caregiver",
            color = AppColors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onReload) { Text("Actualiser", fontSize = 13.sp) }
    }
}

/** Lien vide (réglage effacé) : on le dit, au lieu d'une page blanche. */
@Composable
private fun CaregiverMissingLink() {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Aucun lien de suivi", color = AppColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Renseigne le lien du site et l'identifiant du flux dans Réglages → Suivi parent, " +
                "puis reviens sur cette page.",
            color = AppColors.textMuted,
            fontSize = 13.sp,
        )
    }
}

/** La page du site, telle quelle : JavaScript et stockage local, fond noir. */
@Composable
private fun CaregiverWeb(web: WebView, modifier: Modifier) {
    AndroidView(factory = { web }, modifier = modifier)
}

@SuppressLint("SetJavaScriptEnabled")
private fun caregiverWeb(context: Context, link: String): WebView = WebView(context).apply {
    setBackgroundColor(Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    webViewClient = WebViewClient()
    loadUrl(link)
}
