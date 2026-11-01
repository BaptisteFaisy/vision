package dev.sam3vision.core.settings

/**
 * Invite pasted by the loved one (Caregiver role): the link or code the
 * blind person sent them. Three accepted shapes, shortest to fullest:
 * - the bare code: "leo"
 * - the lone parameter: "?share=leo"
 * - the full link: "https://suivi-parent.vercel.app/?share=leo"
 *
 * Pure and JVM-tested ([CaregiverInviteTest]). `null` = empty field, nothing to follow.
 */
data class CaregiverInvite(val siteUrl: String, val shareId: String)

fun parseCaregiverInvite(raw: String): CaregiverInvite? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val shareAt = text.indexOf("share=")
    if (shareAt < 0) {
        // No parameter: bare code ("leo") or lone site (the id will follow).
        return if (looksLikeUrl(text)) CaregiverInvite(siteUrl = text, shareId = "") else
            CaregiverInvite(siteUrl = "", shareId = text)
    }
    val site = text.substring(0, shareAt).trimEnd('?', '&', ' ')
    val id = text.substring(shareAt + "share=".length).substringBefore('&').trim()
    return CaregiverInvite(siteUrl = site, shareId = id)
}

/** A link starts with a scheme or "www."; the rest is a code. */
private fun looksLikeUrl(text: String): Boolean =
    text.contains("://") || text.startsWith("www.", ignoreCase = true)
