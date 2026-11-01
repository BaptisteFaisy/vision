package dev.sam3vision.app.isee

import dev.sam3vision.core.share.SharedPhoto

/**
 * Latest JPEG frame of the iSee camera, for the loved one's page: 320 px
 * base64 (slow photo) and 480 px binary (live).
 *
 * The camera only yields frames while it serves ([IseeCameraWiring]): this
 * reference copy costs nothing and keeps the picture out of Compose state
 * (one recomposition per frame at 20-30 fps).
 *
 * A frame is only offered [FRESH_MS]: when recording stops, the page shows
 * the frozen picture briefly then clears it, instead of a stale photo
 * passed off as live.
 */
internal object SharedCameraFrame {

    /** How long a frame still counts as live. */
    private const val FRESH_MS = 6_000L

    @Volatile
    private var jpeg: String? = null

    @Volatile
    private var capturedAtMs: Long = 0L

    /** Frame number: the live stream never resends the same picture. */
    @Volatile
    private var seq: Long = 0L

    /**
     * True when at least one caregiver watches the live stream (announced by
     * the relay): the camera converts frames for the loved one even when SAM
     * wants none.
     */
    @Volatile
    var audience: Boolean = false

    @Volatile
    private var jpegBytes: ByteArray? = null

    @Volatile
    private var bytesAtMs: Long = 0L

    /** Binary frame number: same anti-duplicate rule as [seq], other pipe. */
    @Volatile
    private var bytesSeq: Long = 0L

    /** Stores the frame just converted by the camera analyzer. */
    fun offer(frame: String) {
        jpeg = frame
        capturedAtMs = System.currentTimeMillis()
        seq += 1
    }

    /**
     * Stores the **binary** live frame: same bytes as the JPEG pushed on the
     * WebSocket, with no base64 detour (the copy guards the encoder's reused
     * buffer).
     */
    fun offerBytes(frame: ByteArray) {
        jpegBytes = frame.copyOf()
        bytesAtMs = System.currentTimeMillis()
        bytesSeq += 1
    }

    /**
     * Photo to publish to the loved one: the latest frame when under [FRESH_MS]
     * old, timestamped at send time ([nowMs]) — send freshness is what counts
     * page-side, not sensor freshness.
     */
    fun photoOf(nowMs: Long): SharedPhoto? =
        jpeg?.takeIf { nowMs - capturedAtMs <= FRESH_MS }?.let { SharedPhoto(it, nowMs) }

    /**
     * Frame to push live: its number and base64, while just captured
     * ([maxAgeMs]). `null` = closed or frozen camera, nothing to send — the
     * live stream stops itself rather than looping a picture.
     */
    fun latest(nowMs: Long, maxAgeMs: Long = dev.sam3vision.core.share.STREAM_FRAME_MAX_AGE_MS): Frame? =
        jpeg?.takeIf { nowMs - capturedAtMs <= maxAgeMs }?.let { Frame(seq, it) }

    /** A frame ready for the live stream. */
    data class Frame(val seq: Long, val jpegBase64: String)

    /**
     * Binary frame to push live: number and JPEG bytes, while just captured
     * ([maxAgeMs]). `null` = nothing fresh, the live stream stops itself
     * rather than looping a picture.
     */
    fun latestBytes(nowMs: Long, maxAgeMs: Long = dev.sam3vision.core.share.STREAM_FRAME_MAX_AGE_MS): FrameBytes? =
        jpegBytes?.takeIf { nowMs - bytesAtMs <= maxAgeMs }?.let { FrameBytes(bytesSeq, it) }

    /** A binary frame ready for the live stream. */
    data class FrameBytes(val seq: Long, val jpeg: ByteArray)

    /** Camera closed: nothing left to show the loved one. */
    fun clear() {
        jpeg = null
        capturedAtMs = 0L
        jpegBytes = null
        bytesAtMs = 0L
    }
}
