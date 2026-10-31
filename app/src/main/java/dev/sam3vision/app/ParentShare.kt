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
 * Partage le suivi parent : ouvre WhatsApp avec le lien du site pré-rempli.
 * Sans WhatsApp, bascule sur wa.me dans le navigateur, puis sur l'app Messages
 * (SMS), puis sur le sélecteur Android générique. Le lien porte l'identifiant
 * **unique de ce téléphone**, attribué à l'installation : aucun prénom n'est
 * écrit en dur, rien n'est à renommer avant de partager.
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
    // Ni WhatsApp ni navigateur : bascule sur l'app Messages (SMS, destinataire à choisir).
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
 * Seul l'identifiant reçu choisit le suivi : ni le flux local de ce téléphone, ni
 * l'origine du lien ne décident quoi afficher. Le lien est reconstruit sur le relais
 * de l'app, donc un ancien lien `suivi-parent.vercel.app` désigne encore le bon
 * proche alors que ce site ne sert plus la carte.
 */
fun caregiverShareLink(settings: Settings): String =
    shareIdOf(settings.caregiverInvite)?.let { relayShareLink(it) }
        ?: shareIdOf(settings.parentSiteUrl)?.let { relayShareLink(it) }
        ?: ""

/**
 * Lien envoyé au proche : le relais lit `?share=` pour ouvrir le bon flux, sur la
 * base des réglages (relais de l'app par défaut). Sans identifiant propre à ce
 * téléphone, aucun flux n'est deviné : l'ancien défaut commun « leo » n'est plus
 * jamais réutilisé, sinon le proche suivrait le téléphone d'un autre.
 */
fun shareLink(siteUrl: String, shareId: String): String {
    val id = normalizeShareId(shareId)
    if (id.isEmpty()) return relayBaseOf(siteUrl)
    return relayShareLink(id, siteUrl) ?: relayShareLink(id) ?: Settings.DEFAULT_PARENT_SITE_URL
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
