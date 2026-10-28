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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.sam3vision.core.share.caregiverPageUrl

/** La page Portrait embarquée dans l'APK ; son lien choisit uniquement le flux distant. */
@Composable
fun CaregiverScreen(link: String, onBack: () -> Unit, mapsKey: String) {
    val context = LocalContext.current
    val openSettings by rememberUpdatedState(onBack)
    val pageUrl = remember(link, mapsKey) { caregiverPageUrl(link, mapsKey) }
    val web = remember(pageUrl) { caregiverWeb(context, pageUrl) { openSettings() } }
    BackHandler { openSettings() }
    DisposableEffect(web) {
        onDispose { web.stopLoading(); web.destroy() }
    }
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        key(web) { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun caregiverWeb(context: Context, pageUrl: String, onSettings: () -> Unit): WebView = WebView(context).apply {
    // WRAP_CONTENT laisse le viewport CSS à 0, même si Compose mesure la vue plein écran.
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    setBackgroundColor(Color.WHITE)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    webViewClient = CaregiverWebClient(context.assets, pageUrl, onSettings)
    loadUrl(pageUrl)
}
