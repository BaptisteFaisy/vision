package dev.sam3vision.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sam3vision.core.share.caregiverPageUrl

/** La page Portrait embarquée dans l'APK ; son lien choisit uniquement le flux distant. */
@Composable
fun CaregiverScreen(link: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val openSettings by rememberUpdatedState(onBack)
    val pageUrl = remember(link) { caregiverPageUrl(link) }
    var loading by remember(pageUrl) { mutableStateOf(true) }
    var error by remember(pageUrl) { mutableStateOf<String?>(null) }
    val web = remember(pageUrl) {
        caregiverWeb(context, pageUrl, { openSettings() }, onLoading = { loading = it }, onError = { error = it })
    }
    BackHandler { openSettings() }
    DisposableEffect(web) {
        onDispose { web.stopLoading(); web.destroy() }
    }
    // Pas de navigationBarsPadding : la page gère elle-même l'encoche basse
    // (caméra plein-bleed jusqu'au bord) ; un padding Compose laisserait une
    // bande blanche sous l'image.
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
            .statusBarsPadding(),
    ) {
        key(web) { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (error != null && !loading) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Map unavailable", fontSize = 18.sp)
                Text(error!!, fontSize = 13.sp)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun caregiverWeb(
    context: Context,
    pageUrl: String,
    onSettings: () -> Unit,
    onLoading: (Boolean) -> Unit,
    onError: (String?) -> Unit,
): WebView = WebView(context).apply {
    // WRAP_CONTENT laisse le viewport CSS à 0, même si Compose mesure la vue plein écran.
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    setBackgroundColor(Color.WHITE)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    // L'image du proche se zoome au pincement : la WebView ne doit pas le bloquer.
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    webViewClient = CaregiverWebClient(context.assets, pageUrl, onSettings, onLoading, onError)
    loadUrl(pageUrl)
}
