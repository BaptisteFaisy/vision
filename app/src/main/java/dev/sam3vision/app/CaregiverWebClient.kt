package dev.sam3vision.app

import android.content.res.AssetManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.sam3vision.core.share.caregiverAssetName
import java.io.IOException

/** UI de la version installée ; /api/state reste servi par le téléphone partagé via le relais. */
internal class CaregiverWebClient(
    private val assets: AssetManager,
    private val pageUrl: String,
    private val onOpenSettings: () -> Unit,
    private val onLoading: (Boolean) -> Unit = {},
    private val onError: (String?) -> Unit = {},
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        onError(null)
        onLoading(true)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onLoading(false)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            onLoading(false)
            onError("La page n'a pas chargé (${error.errorCode}) : vérifie la connexion, puis rouvre la page.")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) {
            onLoading(false)
            onError("Le site répond ${response.statusCode} : le lien de suivi est peut-être faux, puis rouvre la page.")
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val name = caregiverAssetName(request.url.toString(), pageUrl) ?: return null
        return try {
            WebResourceResponse(mimeType(name), "UTF-8", assets.open("caregiver/$name"))
        } catch (_: IOException) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.scheme != "isee-caregiver" || uri.host != "settings") return false
        view.post { onOpenSettings() }
        return true
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.')) {
        "html" -> "text/html"
        "css" -> "text/css"
        "mjs" -> "text/javascript"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
}
