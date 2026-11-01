package dev.sam3vision.core.share

import dev.sam3vision.core.settings.Settings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer

const val CAREGIVER_ASSET_PREFIX = "/.isee-caregiver/"

/** Only id accepted by the relay and by `?share=`. */
private val SHARE_ID = Regex("[a-z0-9-]{1,32}")

/**
 * Sanitized stream id: the relay only accepts `[a-z0-9-]{1,32}` and reads
 * `?share=` as-is. Without this, "Léo" went out in POST but answered 400,
 * while the page read another stream — black tracking on both sides, not a word.
 */
fun normalizeShareId(raw: String): String {
    val ascii = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFD)
    return ascii.replace(Regex("[\\u0300-\\u036f]"), "")
}

/**
 * Stream id carried by an invite: full link (`?share=`), lone parameter
 * (`?share=leo`) or bare code (`leo`). `null` = nothing usable: a link
 * without an id **never** falls back to this phone's local stream.
 */
fun shareIdOf(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val fromLink = Regex("[?&]share=([^&#\\s]+)").find(text)?.groupValues?.get(1)
    val code = fromLink ?: text.takeIf { !it.contains('/') && !it.contains(':') }
    return code?.let(::normalizeShareId)?.takeIf(SHARE_ID::matches)
}

/**
 * Tracking link of an id, **always rebuilt on the app relay**
 * ([Settings.DEFAULT_PARENT_SITE_URL]). An old link (`suivi-parent.vercel.app`,
 * `…freebuff.page`) only keeps its id: those hosts no longer serve anything
 * and refuse writes, so reopening it would show nothing.
 */
fun relayShareLink(shareId: String, base: String = Settings.DEFAULT_PARENT_SITE_URL): String? {
    val id = normalizeShareId(shareId)
    if (!SHARE_ID.matches(id)) return null
    val url = base.trim().toHttpUrlOrNull() ?: return null
    return url.newBuilder().encodedPath("/").query(null).fragment(null)
        .setQueryParameter("share", id)
        .build()
        .toString()
}

/**
 * Old relays, abandoned: the Vercel site (frozen deploy, refused writes)
 * and the free `…freebuff.page` host (502 "Site unavailable" on the evening
 * of 29/10, while the Worker still answered). A link from before the switch
 * only keeps its id: reopening it would show nothing since the page calls
 * `/api/state` there.
 */
private val LEGACY_RELAY_HOSTS = setOf("suivi-parent.vercel.app")

/** Abandoned free-hosting suffixes: the site id varies, the host does not. */
private val LEGACY_RELAY_SUFFIXES = listOf(".freebuff.page")

/** True when the URL comes from an abandoned relay, whatever its path (`?share=` included). */
fun isLegacyRelayUrl(url: String): Boolean {
    val at = url.indexOf("://")
    val host = url.substring(if (at >= 0) at + 3 else 0)
        .substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
    return host in LEGACY_RELAY_HOSTS || LEGACY_RELAY_SUFFIXES.any(host::endsWith)
}

/**
 * Base actually used to publish: an empty URL, or one inherited from the
 * abandoned site, falls back to the app relay. Without this, a pre-switch
 * install kept posting into the void until the Settings screen opened.
 */
fun relayBaseOf(url: String): String =
    if (url.isBlank() || isLegacyRelayUrl(url)) Settings.DEFAULT_PARENT_SITE_URL else url

/**
 * Same origin as the relay: the page comes from the APK, `/api/state` calls
 * from the relay. Only `share` (the stream) and `embedded` cross the URL; an
 * old map-background key (`key`, map removed) is stripped from the received link.
 */
fun caregiverPageUrl(link: String): String {
    val url = link.toHttpUrlOrNull() ?: Settings.DEFAULT_PARENT_SITE_URL.toHttpUrlOrNull()!!
    return url.newBuilder().encodedPath(CAREGIVER_ASSET_PREFIX + "index.html")
        .fragment(null).removeAllQueryParameters("key").removeAllQueryParameters("embedded")
        .addQueryParameter("embedded", "1")
        .build().toString()
}

/** Only the embedded UI files may be read from assets/. */
fun caregiverAssetName(url: String, pageUrl: String): String? {
    val request = url.toHttpUrlOrNull() ?: return null
    val page = pageUrl.toHttpUrlOrNull() ?: return null
    if (request.scheme != page.scheme || request.host != page.host || request.port != page.port) return null
    if (!request.encodedPath.startsWith(CAREGIVER_ASSET_PREFIX)) return null
    val name = request.encodedPath.removePrefix(CAREGIVER_ASSET_PREFIX)
    return name.takeIf { Regex("[a-zA-Z0-9_-]+\\.(html|css|mjs|png)").matches(it) }
}
