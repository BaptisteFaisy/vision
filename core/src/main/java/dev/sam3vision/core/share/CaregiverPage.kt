package dev.sam3vision.core.share

import dev.sam3vision.core.settings.Settings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer

const val CAREGIVER_ASSET_PREFIX = "/.isee-caregiver/"

/** Seul identifiant accepté par le relais et par `?share=`. */
private val SHARE_ID = Regex("[a-z0-9-]{1,32}")

/**
 * Identifiant de flux assaini : le relais n'accepte que `[a-z0-9-]{1,32}` et lit
 * `?share=` tel quel. Sans ça, « Léo » partait en POST mais répondait 400, pendant
 * que la page lisait un autre flux — suivi noir des deux côtés, sans un mot.
 */
fun normalizeShareId(raw: String): String {
    val ascii = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFD)
    return ascii.replace(Regex("[\\u0300-\\u036f]"), "")
}

/**
 * Identifiant de flux porté par une invitation : lien complet (`?share=`), paramètre
 * seul (`?share=leo`) ou code nu (`leo`). `null` = rien d'exploitable : un lien sans
 * identifiant ne retombe **jamais** sur le flux local de ce téléphone.
 */
fun shareIdOf(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val fromLink = Regex("[?&]share=([^&#\\s]+)").find(text)?.groupValues?.get(1)
    val code = fromLink ?: text.takeIf { !it.contains('/') && !it.contains(':') }
    return code?.let(::normalizeShareId)?.takeIf(SHARE_ID::matches)
}

/**
 * Lien de suivi d'un identifiant, **toujours reconstruit sur le relais de l'app**
 * ([Settings.DEFAULT_PARENT_SITE_URL]). Un ancien lien du site
 * (`suivi-parent.vercel.app`) n'en garde que son identifiant : ce site ne sert plus
 * la carte et refuse les écritures, donc le rouvrir ne montrerait rien.
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

/** Ancien relais (site Vercel, déploiement figé, écritures refusées). */
private const val LEGACY_RELAY_HOST = "suivi-parent.vercel.app"

/** Vrai si l'URL vient de l'ancien site, quel que soit son chemin (`?share=` compris). */
fun isLegacyRelayUrl(url: String): Boolean {
    val at = url.indexOf("://")
    val host = url.substring(if (at >= 0) at + 3 else 0)
        .substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
    return host == LEGACY_RELAY_HOST
}

/**
 * Base réellement utilisée pour publier : une URL vide ou héritée du site abandonné
 * retombe sur le relais de l'app. Sans ça, une installation d'avant la bascule
 * continuait de poster dans le vide jusqu'à ce que l'écran des Réglages s'ouvre.
 */
fun relayBaseOf(url: String): String =
    if (url.isBlank() || isLegacyRelayUrl(url)) Settings.DEFAULT_PARENT_SITE_URL else url

/**
 * Même origine que le relais : la page vient de l'APK, les appels `/api/state` du
 * relais. Seuls `share` (le flux) et `embedded` traversent l'URL ; une ancienne
 * clé de fond de carte (`key`, carte supprimée) est retirée du lien reçu.
 */
fun caregiverPageUrl(link: String): String {
    val url = link.toHttpUrlOrNull() ?: Settings.DEFAULT_PARENT_SITE_URL.toHttpUrlOrNull()!!
    return url.newBuilder().encodedPath(CAREGIVER_ASSET_PREFIX + "index.html")
        .fragment(null).removeAllQueryParameters("key").removeAllQueryParameters("embedded")
        .addQueryParameter("embedded", "1")
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
