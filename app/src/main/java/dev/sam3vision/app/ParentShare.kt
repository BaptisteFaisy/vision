package dev.sam3vision.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.normalizeShareId
import dev.sam3vision.core.share.relayBaseOf
import dev.sam3vision.core.share.relayShareLink
import dev.sam3vision.core.share.shareIdOf
import java.net.URLEncoder

/**
 * Shares parent tracking: opens WhatsApp with the site link prefilled.
 * Without WhatsApp, falls back to wa.me in the browser, then to the Messages
 * app (SMS), then to the generic Android picker. The link carries this
 * phone's **unique** id, granted at install: no name is hardcoded,
 * nothing needs renaming before sharing.
 */
fun shareParentSite(context: Context, siteUrl: String, shareId: String) {
    val link = shareLink(siteUrl, shareId)
    val text = "Live tracking: $link"
    val whatsapp = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .setPackage("com.whatsapp")
        .putExtra(Intent.EXTRA_TEXT, text)
    if (startShare(context, whatsapp)) return
    val encoded = URLEncoder.encode(text, Charsets.UTF_8.name())
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded"))
    if (startShare(context, web)) return
    // Neither WhatsApp nor browser: fall back to the Messages app (SMS, pick a recipient).
    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
        .putExtra("sms_body", text)
    if (startShare(context, sms)) return
    val chooser = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        "Share tracking",
    )
    startShare(context, chooser)
}

/**
 * Only the received id picks the tracking: neither this phone's local stream
 * nor the link origin decides what shows. The link is rebuilt on the app
 * relay, so an old `suivi-parent.vercel.app` link still names the right
 * loved one even though that site no longer serves the map.
 */
fun caregiverShareLink(settings: Settings): String =
    shareIdOf(settings.caregiverInvite)?.let { relayShareLink(it) }
        ?: shareIdOf(settings.parentSiteUrl)?.let { relayShareLink(it) }
        ?: ""

/**
 * Link sent to the loved one: the relay reads `?share=` to open the right
 * stream, on the settings base (app relay by default). With no id of this
 * phone's own, no stream is ever guessed: the old shared "leo" default is
 * never reused, or the loved one would follow someone else's phone.
 */
fun shareLink(siteUrl: String, shareId: String): String {
    val id = normalizeShareId(shareId)
    if (id.isEmpty()) return relayBaseOf(siteUrl)
    return relayShareLink(id, siteUrl) ?: relayShareLink(id) ?: Settings.DEFAULT_PARENT_SITE_URL
}

/** Fires a share; `false` when no app takes it. */
private fun startShare(context: Context, intent: Intent): Boolean {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
