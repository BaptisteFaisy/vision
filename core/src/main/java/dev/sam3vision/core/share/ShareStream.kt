package dev.sam3vision.core.share

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Live rate: **15 frames per second**. The caregiver page follows a walk, not
 * a movie — but under 10 fps, motion stutters and the caregiver loses track
 * of the loved one's moves. The real rate is measured at the `direct:` log
 * (median of sends), never assumed from this constant.
 */
const val STREAM_FRAME_MS = 66L

/**
 * A frame older than this is no longer pushed: closed or frozen camera, the
 * live stream stops itself instead of looping the same picture. At 15 fps,
 * a frame over 4 turns old has nothing live left.
 */
const val STREAM_FRAME_MAX_AGE_MS = 300L

/** Wait before reopening the live stream after a drop. */
const val STREAM_RETRY_MS = 2_000L

/**
 * Empty pump turns in a row before announcing the camera off: a micro-gap
 * (slow encoder, GC, saturated executor) must never clear the caregivers'
 * picture — only about a second of silence sends the `false` notice.
 * At 15 fps, 15 turns ≈ 1 s.
 */
const val STREAM_CAMERA_OFF_TURNS = 15

/**
 * True when silence lasts long enough to announce the camera off: under the
 * threshold, the page keeps its last picture and the gap stays invisible.
 */
fun cameraOffReady(emptyTurns: Int): Boolean = emptyTurns >= STREAM_CAMERA_OFF_TURNS

/**
 * Live URL (`/ws?role=push`): same origin as the relay, `wss` when it is
 * `https`. The write key travels in the URL — the same secret as
 * `/api/share`, and the relay compares it to the stream before taking frames.
 *
 * `null` when the base is no URL or the id is invalid: no live stream beats
 * a live stream on someone else's feed.
 */
fun streamUrlOf(base: String, shareId: String, writeKey: String): String? {
    val url = base.trim().toHttpUrlOrNull() ?: return null
    val id = normalizeShareId(shareId)
    if (!SHARE_ID.matches(id)) return null
    // `HttpUrl` refuses the `wss` scheme: build in `https` then swap the
    // prefix — host, port and parameter encoding stay the same.
    val plain = url.newBuilder().encodedPath("/ws").query(null).fragment(null)
        .setQueryParameter("share", id)
        .addQueryParameter("role", "push")
        .addQueryParameter("key", writeKey)
        .build().toString()
    return if (url.isHttps) plain.replaceFirst("https://", "wss://")
    else plain.replaceFirst("http://", "ws://")
}

/** Caregiver count announced by the relay, `null` when the message is not one. */
fun audienceCountOf(text: String): Int? =
    AUDIENCE.find(text)?.groupValues?.get(1)?.toIntOrNull()

/**
 * True when at least one caregiver watches: the phone **only emits then**.
 * Without this rule, the loved one's camera would push pictures into the
 * void on every walk, off their plan and battery.
 */
fun audienceWatching(text: String): Boolean? = audienceCountOf(text)?.let { it > 0 }

/** `{"type":"audience","watch":1}` — the only text message the relay sends the phone. */
private val AUDIENCE = Regex("\"watch\"\\s*:\\s*(\\d{1,9})")

/**
 * Camera announcement to the relay: `{"type":"camera","on":…}`, same shape as
 * the audience notice. The room forwards it to caregivers, who clear the
 * picture on `on:false` instead of waiting for the last frame to expire.
 */
fun cameraNotice(on: Boolean): String = "{\"type\":\"camera\",\"on\":$on}"

/** Only id accepted by the relay; same shape as `CaregiverPage`. */
private val SHARE_ID = Regex("[a-z0-9-]{1,32}")
