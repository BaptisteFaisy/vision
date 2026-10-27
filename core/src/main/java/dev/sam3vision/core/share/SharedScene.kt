package dev.sam3vision.core.share

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import dev.sam3vision.core.settings.CaregiverRole
import dev.sam3vision.core.settings.Settings

/** Phrase courante du téléphone accompagné, sans historique ni données du caregiver. */
data class SharedScene(
    val text: String,
    val fromUser: Boolean = false,
    val speaking: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text.take(MAX_SHARED_TEXT))
        .put("fromUser", fromUser)
        .put("speaking", speaking)
}

const val MAX_SHARED_TEXT = 2_000

/** Un appareil caregiver ne doit jamais remplacer la position du téléphone accompagné. */
fun canPublishShare(settings: Settings): Boolean =
    settings.shareEnabled && settings.caregiverRole == CaregiverRole.Blind

/** Un caregiver choisit le flux du lien reçu, jamais l'identifiant local de son téléphone. */
fun validatedShareLink(value: String): String? {
    val url = value.trim().toHttpUrlOrNull() ?: return null
    val id = url.queryParameter("share") ?: return null
    if (!Regex("[a-z0-9-]{1,32}").matches(id)) return null
    return url.toString()
}

/** Le lien partagé peut avoir une query ; le relais reste à la racine de son origine. */
fun shareEndpoint(siteUrl: String): String? = siteUrl.trim().toHttpUrlOrNull()
    ?.newBuilder()?.encodedPath("/api/share")?.query(null)?.fragment(null)?.build()?.toString()
