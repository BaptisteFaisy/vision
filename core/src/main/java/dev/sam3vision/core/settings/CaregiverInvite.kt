package dev.sam3vision.core.settings

/**
 * Invitation collée par le proche (rôle Caregiver) : le lien ou le code que la
 * personne aveugle lui a envoyé. Trois formes acceptées, du plus court au complet :
 * - le code nu : « leo »
 * - le paramètre seul : « ?share=leo »
 * - le lien complet : « https://suivi-parent.vercel.app/?share=leo »
 *
 * Pur et testé en JVM ([CaregiverInviteTest]). `null` = champ vide, rien à suivre.
 */
data class CaregiverInvite(val siteUrl: String, val shareId: String)

fun parseCaregiverInvite(raw: String): CaregiverInvite? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val shareAt = text.indexOf("share=")
    if (shareAt < 0) {
        // Pas de paramètre : code nu (« leo ») ou site seul (l'identifiant suivra).
        return if (looksLikeUrl(text)) CaregiverInvite(siteUrl = text, shareId = "") else
            CaregiverInvite(siteUrl = "", shareId = text)
    }
    val site = text.substring(0, shareAt).trimEnd('?', '&', ' ')
    val id = text.substring(shareAt + "share=".length).substringBefore('&').trim()
    return CaregiverInvite(siteUrl = site, shareId = id)
}

/** Un lien commence par un schéma ou « www.» ; le reste est un code. */
private fun looksLikeUrl(text: String): Boolean =
    text.contains("://") || text.startsWith("www.", ignoreCase = true)
