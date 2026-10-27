package dev.sam3vision.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.parseCaregiverInvite
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
 * Lien ouvert par la page Caregiver : l'invitation collée dans les réglages gagne
 * sur les champs historiques — lien complet (tel quel), code nu (greffé sur le
 * site enregistré), site seul (avec l'identifiant enregistré). Sans invitation,
 * le site et l'identifiant enregistrés s'appliquent comme avant.
 */
fun caregiverShareLink(settings: Settings): String {
    val invite = parseCaregiverInvite(settings.caregiverInvite) ?: return shareLink(settings.parentSiteUrl, settings.shareId)
    if (invite.shareId.isBlank()) return shareLink(invite.siteUrl, settings.shareId)
    if (invite.siteUrl.isBlank()) return shareLink(settings.parentSiteUrl, invite.shareId)
    return shareLink(invite.siteUrl, invite.shareId)
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
