package dev.sam3vision.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.parseCaregiverInvite
import dev.sam3vision.core.share.validatedShareLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * Partage le suivi parent : ouvre WhatsApp avec le lien du site pré-rempli.
 * Sans WhatsApp, bascule sur wa.me dans le navigateur, puis sur l'app Messages
 * (SMS), puis sur le sélecteur Android générique. Lien générique (Réglages) :
 * pas de cloisonnement par proche.
 */
fun shareParentSite(context: Context, siteUrl: String, shareId: String) {
    val link = shareLink(siteUrl, shareId)
    val text = "Suivi de Léo en direct : $link"
    val whatsapp = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .setPackage("com.whatsapp")
        .putExtra(Intent.EXTRA_TEXT, text)
    if (startShare(context, whatsapp)) return
    val encoded = URLEncoder.encode(text, Charsets.UTF_8.name())
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded"))
    if (startShare(context, web)) return
    // Ni WhatsApp ni navigateur : bascule sur l'app Messages (SMS, destinataire à choisir).
    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
        .putExtra("sms_body", text)
    if (startShare(context, sms)) return
    val chooser = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        "Partager le suivi",
    )
    startShare(context, chooser)
}

/**
 * Seul un lien ou code reçu choisit le suivi ; l'identifiant de ce téléphone
 * ne doit jamais servir à deviner celui du proche. Un ancien lien complet
 * enregistré dans parentSiteUrl reste utilisable après mise à jour.
 */
fun caregiverShareLink(settings: Settings): String {
    val raw = settings.caregiverInvite.trim()
    validatedShareLink(raw)?.let { return it }
    val invite = parseCaregiverInvite(raw)
        ?: return validatedShareLink(settings.parentSiteUrl).orEmpty()
    if (invite.siteUrl.isNotBlank() || invite.shareId.isBlank()) return ""
    val site = settings.parentSiteUrl.toHttpUrlOrNull() ?: return ""
    return validatedShareLink(site.newBuilder().setQueryParameter("share", invite.shareId).build().toString()).orEmpty()
}

/**
 * Lien envoyé au proche : le site lit `?share=` pour ouvrir le bon flux.
 * Un lien déjà paramétré (token manuel) est conservé tel quel.
 */
fun shareLink(siteUrl: String, shareId: String): String {
    val base = siteUrl.ifBlank { Settings.DEFAULT_PARENT_SITE_URL }
    val id = shareId.ifBlank { Settings.DEFAULT_SHARE_ID }
    if (base.contains("?share=") || base.contains("&share=")) return base
    val sep = if (base.contains("?")) "&" else "?"
    return base.trim().trimEnd('/') + sep + "share=" + id.trim()
}

/** Lance un partage ; `false` si aucune app ne le prend en charge. */
private fun startShare(context: Context, intent: Intent): Boolean {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
