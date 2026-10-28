package dev.sam3vision.core.share

import dev.sam3vision.core.settings.Settings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val CAREGIVER_ASSET_PREFIX = "/.isee-caregiver/"

/** Même origine que le lien reçu : le relais reste distant, l'interface vient de l'APK. */
fun caregiverPageUrl(link: String, mapsKey: String): String {
    val url = link.toHttpUrlOrNull() ?: Settings.DEFAULT_PARENT_SITE_URL.toHttpUrlOrNull()!!
    return url.newBuilder().encodedPath(CAREGIVER_ASSET_PREFIX + "index.html")
        .fragment(null).removeAllQueryParameters("key").removeAllQueryParameters("embedded")
        .addQueryParameter("embedded", "1")
        .apply { if (mapsKey.isNotBlank()) addQueryParameter("key", mapsKey) }
        .build().toString()
}

/** Seuls les fichiers de l'interface embarquée peuvent être lus dans assets/. */
fun caregiverAssetName(url: String, pageUrl: String): String? {
    val request = url.toHttpUrlOrNull() ?: return null
    val page = pageUrl.toHttpUrlOrNull() ?: return null
    if (request.scheme != page.scheme || request.host != page.host || request.port != page.port) return null
    if (!request.encodedPath.startsWith(CAREGIVER_ASSET_PREFIX)) return null
    val name = request.encodedPath.removePrefix(CAREGIVER_ASSET_PREFIX)
    return name.takeIf { Regex("[a-zA-Z0-9_-]+\\.(html|css|mjs|png)").matches(it) }
}
