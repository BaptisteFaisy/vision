package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.ShareReporter
import dev.sam3vision.core.share.SharedPhoto
import dev.sam3vision.core.share.SharedPictos
import dev.sam3vision.core.share.SharedScene
import dev.sam3vision.core.share.SharedTrip
import dev.sam3vision.core.share.canPublishShare
import dev.sam3vision.core.share.normalizeShareId
import dev.sam3vision.core.share.relayBaseOf
import dev.sam3vision.core.share.shareEndpoint
import dev.sam3vision.core.share.shareJson
import dev.sam3vision.core.share.sharedObjectsOf

/**
 * Relay to the parent site, driven by the iSee and Direct screens (not the
 * ViewModel, to keep [LiveViewModel] under the size limit): GPS position +
 * heading ([SharePosition]) and objects in absolute heading, at most every 2 s.
 *
 * Silent with no position (refused permission), unreachable relay or cut
 * share: the site then keeps the last state and shows it stale.
 */
class ShareBroadcaster(context: Context) {

    private val positions = SharePosition(context)
    private val reporter = ShareReporter()

    /** Starts or cuts the GPS listen following the Settings switch. */
    fun start(enabled: Boolean) {
        reporter.reset()
        if (enabled) positions.start() else positions.stop()
    }

    /** Cuts the listen and forgets the position (leaving the Direct). */
    fun stop() {
        positions.stop()
        reporter.reset()
    }

    /**
     * Sends the current state. With no position (GPS not locked yet, indoors,
     * refused permission), camera, sentence and landmarks still go: otherwise
     * the caregiver page stayed OFFLINE while the camera ran. With no measured
     * object (null layer, unknown calibration, empty scene), the position
     * still goes.
     * The id is normalized (`normalizeShareId`): raw, "Léo" answers 400
     * relay-side. A cleared site field falls back to the default site.
     * [photo], [trip] and [pictos] are optional: with no camera, outside
     * guidance or with no recognized landmark, the packet stays the earlier
     * one (position, objects, sentence).
     */
    suspend fun publish(
        nowMs: Long,
        settings: Settings,
        layer: DistanceLayer?,
        scene: SharedScene? = null,
        trip: SharedTrip? = null,
        photo: SharedPhoto? = null,
        pictos: SharedPictos? = null,
    ) {
        if (!canPublishShare(settings)) return
        val id = normalizeShareId(settings.shareId)
        if (id.isBlank()) return
        val fix = positions.lastFix
        val objects = layer?.let { sharedObjectsOf(it.readings, it.width, it.height, it.fovDeg, fix?.headingDeg) }.orEmpty()
        val url = shareEndpoint(relayBaseOf(settings.parentSiteUrl)) ?: return
        ShareHealth.note(
            reporter.maybeSend(
                nowMs,
                url,
                shareJson(id, settings.shareWriteKey, fix, objects, scene, photo, trip, pictos),
            ),
        )
    }

}
