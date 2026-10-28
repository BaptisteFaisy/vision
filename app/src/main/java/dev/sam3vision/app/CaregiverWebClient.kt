package dev.sam3vision.app

import android.content.res.AssetManager
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
) : WebViewClient() {
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
