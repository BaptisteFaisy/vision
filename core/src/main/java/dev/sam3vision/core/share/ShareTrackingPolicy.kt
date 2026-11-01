package dev.sam3vision.core.share

/**
 * Rate policy of the permanent tracking service (battery): pure and
 * device-free testable. Screen on = normal share rate; screen off = lone
 * spaced-out position; while the Direct publishes the screen already sends
 * the full state, so the service just waits.
 */
object ShareTrackingPolicy {

    /** Pocket rate (screen off, app in background). */
    const val SCREEN_OFF_DELAY_MS = 15_000L

    /** Wait while the Direct publishes (quick state recheck). */
    const val SCREEN_BUSY_DELAY_MS = 1_000L

    fun nextDelayMs(interactive: Boolean, directPublishing: Boolean): Long = when {
        directPublishing -> SCREEN_BUSY_DELAY_MS
        interactive -> SHARE_INTERVAL_MS
        else -> SCREEN_OFF_DELAY_MS
    }
}
