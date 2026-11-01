package dev.sam3vision.core.share

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import dev.sam3vision.core.settings.CaregiverRole
import dev.sam3vision.core.settings.Settings

/** Current sentence of the accompanied phone, with no history or caregiver data. */
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

/** A caregiver device must never replace the accompanied phone's position. */
fun canPublishShare(settings: Settings): Boolean =
    settings.shareEnabled && settings.caregiverRole == CaregiverRole.Blind

/** The shared link may carry a query; the relay stays at its origin root. */
fun shareEndpoint(siteUrl: String): String? = siteUrl.trim().toHttpUrlOrNull()
    ?.newBuilder()?.encodedPath("/api/share")?.query(null)?.fragment(null)?.build()?.toString()
